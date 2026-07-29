package eu.wohlben.qits.dns.wire;

import eu.wohlben.qits.dns.resolve.RecordData;
import eu.wohlben.qits.dns.resolve.ResolutionResult;
import eu.wohlben.qits.dns.resolve.ResponseCode;
import eu.wohlben.qits.dns.resolve.SoaData;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import org.jboss.logging.Logger;
import org.xbill.DNS.AAAARecord;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Header;
import org.xbill.DNS.Message;
import org.xbill.DNS.MessageSizeExceededException;
import org.xbill.DNS.NSRecord;
import org.xbill.DNS.Name;
import org.xbill.DNS.OPTRecord;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.SOARecord;
import org.xbill.DNS.Section;
import org.xbill.DNS.TextParseException;

/**
 * The {@link DnsCodec} over dnsjava, and the only place in {@code src/main} that names an {@code
 * org.xbill.DNS} type.
 *
 * <p>The import list is the design. It is {@code Message}, {@code Name}, {@code Record} and the
 * concrete record classes — parse and encode, nothing else. {@code Lookup}, {@code Resolver},
 * {@code SimpleResolver} and {@code ResolverConfig} are absent and must stay absent: that half of
 * dnsjava is where the {@code ServiceLoader} hooks and the system-configuration reflection live, it
 * drags {@code android.net.ConnectivityManager} and {@code com.sun.jna.Pointer} in behind it, and
 * {@code Lookup}'s class initializer reads the BUILD MACHINE's {@code /etc/resolv.conf}. {@code
 * application.properties} strips the service files that make any of it reachable, so an import
 * added here fails the native build rather than shipping a binary configured by whoever compiled
 * it.
 *
 * <p>Two dnsjava behaviours shape the code below and are not obvious from its signatures:
 *
 * <ul>
 *   <li><b>{@code toWire(int)} sets TC in the BYTES, never in the {@code Message}.</b> Reading
 *       {@code response.getHeader().getFlag(Flags.TC)} after encoding is always false. The only way
 *       to know truncation happened is the two-arg overload, whose second parameter is {@code
 *       truncate} rather than {@code throwOnFail} — inverted from how it reads — so {@code
 *       toWire(n, false)} throws {@link MessageSizeExceededException} and {@code toWire(n)}
 *       truncates. {@link #encode} calls the first, catches, and re-encodes with the second.
 *   <li><b>Truncation is RRset-atomic.</b> On overflow dnsjava rolls back to the start of the
 *       current RRset, so an RRset that alone exceeds the budget is dropped ENTIRELY. Measured: 100
 *       A records sharing one owner encode to 1634 bytes untruncated and to a 34-byte response with
 *       ZERO answers at 512. That is the common shape here — many A rows for one hostname — and it
 *       is correct behaviour, not a bug: a partial RRset is worse than none, and TC=1 tells the
 *       resolver to come back over TCP where the whole set fits.
 * </ul>
 */
@ApplicationScoped
public class DnsjavaCodec implements DnsCodec {

  private static final Logger LOG = Logger.getLogger(DnsjavaCodec.class);

  /**
   * The UDP payload size our OPT advertises. Fixed at §5's 1232 rather than echoed from the query:
   * an OPT in a RESPONSE states what THIS server can receive, and 1232 is what survives the
   * internet's real-world path MTU without fragmenting. Echoing the client's number back would be
   * describing the client's buffer to the client.
   */
  static final int ADVERTISED_PAYLOAD_SIZE = 1232;

  /**
   * The flags on our OPT, and zero on purpose. The one flag that exists is DO, and setting it
   * claims the response carries DNSSEC data — we sign nothing (§2), so echoing a client's DO back
   * would be a claim this server cannot make good on. The DO bit is read, ignored, and not
   * reflected.
   */
  private static final int RESPONSE_OPT_FLAGS = 0;

  @Override
  public DecodedQuery decode(byte[] wire, int length) throws DnsFormatException {
    byte[] exact = length == wire.length ? wire : Arrays.copyOf(wire, length);
    Message query;
    try {
      query = new Message(exact);
    } catch (IOException | RuntimeException e) {
      // RuntimeException as well as IOException, deliberately. These bytes come off a socket facing
      // the open internet, and a parser fed a label-length lie or a compression-pointer loop is
      // entitled to fail with whatever it likes; every one of those is the same decision here.
      throw new DnsFormatException("not a parseable DNS message", e);
    }
    Header header = query.getHeader();
    int qdcount = header.getCount(Section.QUESTION);
    Record question = qdcount == 1 ? query.getQuestion() : null;
    OPTRecord opt = query.getOPT();
    return new DecodedQuery(
        header.getID(),
        // Lowercased and stripped of its trailing dot: that is the spelling the resolution contract
        // is written against. The querier's own capitalisation is not lost — it stays in `exact`
        // and
        // is recovered in encode() by rebuilding the response from the original question.
        question == null ? null : question.getName().toString(true).toLowerCase(Locale.ROOT),
        question == null ? 0 : question.getType(),
        question == null ? 0 : question.getDClass(),
        header.getOpcode(),
        qdcount,
        opt == null ? OptionalInt.empty() : OptionalInt.of(opt.getPayloadSize()),
        exact);
  }

  @Override
  public byte[] encode(DecodedQuery query, ResolutionResult result, int maxLength) {
    Message parsed = reparse(query.wire());
    Record question = parsed.getQuestion();
    Name questionName = question == null ? null : question.getName();

    Message response = new Message(parsed.getHeader().getID());
    Header header = response.getHeader();
    header.setFlag(Flags.QR);
    header.setOpcode(parsed.getHeader().getOpcode());
    if (parsed.getHeader().getFlag(Flags.RD)) {
      // RD is copied from the query into the response (RFC 1035 §4.1.1). It says what the CLIENT
      // asked for, not what we offer — RA below is the one that says what we offer, and it stays 0.
      header.setFlag(Flags.RD);
    }
    if (result.authoritative()) {
      header.setFlag(Flags.AA);
    }
    // RA is never set. This server recurses for nobody, and an open resolver advertising itself is
    // how a small authoritative box becomes someone else's amplifier.
    header.setRcode(rcodeOf(result.rcode()));

    if (question != null) {
      // The ORIGINAL question record, not a reconstruction. Name.toString preserves the parsed
      // spelling while Name.equals is case-insensitive, so copying this object through is what
      // echoes `TeSt.ExAmPle.CoM.` back verbatim — for free, and only as long as nobody re-derives
      // a canonical name from zone data.
      response.addRecord(question, Section.QUESTION);
    }
    addAll(response, result.answers(), questionName, Section.ANSWER);
    addAll(response, result.authority(), questionName, Section.AUTHORITY);

    if (query.ednsPayloadSize().isPresent()) {
      // Added BEFORE encoding: dnsjava reserves an OPT's bytes up front and appends it after the
      // truncated sections, so it survives truncation with no special-casing (measured). An OPT in
      // the response is also what tells the client its own EDNS0 was understood.
      response.addRecord(
          new OPTRecord(ADVERTISED_PAYLOAD_SIZE, 0, 0, RESPONSE_OPT_FLAGS), Section.ADDITIONAL);
    }

    try {
      // `false` is `truncate`, NOT `throwOnFail` — the parameter reads backwards from what it does.
      // toWire(n, true) is silently identical to toWire(n); only this form reports the overflow,
      // and
      // reporting is the only way to know, since TC never lands in the Message object.
      return response.toWire(maxLength, false);
    } catch (MessageSizeExceededException e) {
      byte[] truncated = response.toWire(maxLength);
      LOG.debugf(
          "truncated a %s-byte budget response for %s to %s bytes with TC=1",
          Integer.valueOf(maxLength), query.qname(), Integer.valueOf(truncated.length));
      return truncated;
    }
  }

  /**
   * Re-parses the bytes {@link #decode} already parsed, which is what {@link DecodedQuery#wire()}
   * exists for: the response is built from the ORIGINAL question record so the querier's
   * capitalisation survives, and the seam deliberately does not let a codec's message type cross
   * it.
   *
   * <p>The cost is one extra parse of a message that is at most 512 bytes on the path where it
   * matters, against a seam that survives replacing dnsjava wholesale. A failure here is
   * unreachable — these are the bytes that just parsed — so it is a bug rather than hostile input,
   * and it says so.
   */
  private static Message reparse(byte[] wire) {
    try {
      return new Message(wire);
    } catch (IOException e) {
      throw new IllegalStateException("bytes that decoded a moment ago no longer parse", e);
    }
  }

  private void addAll(Message response, List<RecordData> records, Name question, int section) {
    for (RecordData data : records) {
      try {
        response.addRecord(toRecord(data, question), section);
      } catch (TextParseException | IllegalArgumentException e) {
        // One unencodable row must not cost the whole answer. The API validates values (§3), so
        // reaching this means a row got past validation or a config value is malformed — worth a
        // warning, not worth failing a query that has other records to give.
        LOG.warnf(e, "dropping an unencodable %s record for %s", data.type(), data.owner());
      }
    }
  }

  private static Record toRecord(RecordData data, Name question) throws TextParseException {
    Name owner = owner(data.owner(), question);
    long ttl = data.ttl();
    return switch (data.type()) {
      // The byte[] overloads rather than the InetAddress ones. Both work, but an InetAddress is a
      // type GraalVM refuses in the image heap, and keeping them out of this class entirely means
      // there is no field for anyone to later make `static final`. Inet{4,6}Address.ofLiteral
      // parses
      // WITHOUT resolving (unlike getByName) and throws IllegalArgumentException on anything that
      // is
      // not a literal of that exact family, which is also the type check A-vs-AAAA wants.
      case A ->
          new ARecord(owner, DClass.IN, ttl, Inet4Address.ofLiteral(data.value()).getAddress());
      case AAAA ->
          new AAAARecord(owner, DClass.IN, ttl, Inet6Address.ofLiteral(data.value()).getAddress());
      case CNAME -> new CNAMERecord(owner, DClass.IN, ttl, absolute(data.value()));
      case NS -> new NSRecord(owner, DClass.IN, ttl, absolute(data.value()));
      case SOA -> soa(owner, ttl, data.soa());
    };
  }

  /**
   * The owner name an answer carries. {@link RecordData#owner()} is already the queried name for a
   * wildcard match — expansion is the resolver's job, not ours — so this only decides which OBJECT
   * spells it.
   *
   * <p>Swapping in the question's own {@link Name} when the two are equal is the whole case-echo
   * mechanism: {@code Name.equals} is case-insensitive while {@code toString} preserves the parsed
   * spelling, so this propagates the querier's capitalisation from the question into the answer
   * section, where RFC 1035 §4.1.4 also gets us name compression against it for free.
   */
  private static Name owner(String owner, Name question) throws TextParseException {
    Name name = absolute(owner);
    return question != null && question.equals(name) ? question : name;
  }

  private static SOARecord soa(Name owner, long ttl, SoaData soa) throws TextParseException {
    return new SOARecord(
        owner,
        DClass.IN,
        ttl,
        absolute(soa.mname()),
        mailbox(soa.rname()),
        soa.serial(),
        soa.refresh(),
        soa.retry(),
        soa.expire(),
        soa.minimum());
  }

  /**
   * The SOA's {@code rname} in the wire's mailbox encoding, which is a domain name whose FIRST
   * LABEL is the mail account and whose remainder is the domain: {@code hostmaster@qits.eu} is
   * {@code hostmaster.qits.eu.} on the wire.
   *
   * <p>{@link SoaData} holds it as a plain hostname and leaves the separator to the codec, which is
   * this method. A value already written in the wire's own shape ({@code hostmaster.qits.eu})
   * passes through unchanged; one written the way an operator naturally would ({@code
   * hostmaster@qits.eu}) is converted rather than rejected, because the two spellings mean the same
   * thing and a nameserver that refuses the second over punctuation is a bad trade.
   *
   * <p>A dot INSIDE the account part has to be escaped when the {@code @} form is used — {@code
   * first.last@qits.eu} is {@code first\.last.qits.eu.}, not a four-label name — and that is the
   * one case where the conversion is not cosmetic.
   */
  private static Name mailbox(String rname) throws TextParseException {
    int at = rname.indexOf('@');
    if (at < 0) {
      return absolute(rname);
    }
    return absolute(rname.substring(0, at).replace(".", "\\.") + "." + rname.substring(at + 1));
  }

  /** A name from zone data or config, made absolute. Never null, never relative on the wire. */
  private static Name absolute(String name) throws TextParseException {
    return Name.fromString(name, Name.root);
  }

  private static int rcodeOf(ResponseCode rcode) {
    return switch (rcode) {
      case NOERROR -> Rcode.NOERROR;
      case FORMERR -> Rcode.FORMERR;
      case NOTIMP -> Rcode.NOTIMP;
      case REFUSED -> Rcode.REFUSED;
      case NXDOMAIN -> Rcode.NXDOMAIN;
    };
  }
}
