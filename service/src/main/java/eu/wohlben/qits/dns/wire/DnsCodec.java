package eu.wohlben.qits.dns.wire;

import eu.wohlben.qits.dns.resolve.ResolutionResult;

/**
 * The seam the codec choice lives behind: bytes to question, answer to bytes.
 *
 * <p>It exists so the dnsjava-in-a-native-image question can be answered LATE. dnsjava is the
 * chosen implementation — a two-decade-old parser is the right thing to point at hostile input from
 * the open internet, and the alternative of hand-rolling one for compression-pointer loops,
 * truncated headers and label-length lies is the kind of confidence that gets a socket owned. But
 * whether its codec classes survive native compilation is a spike's finding rather than a fact, and
 * if it fails outright the fallback is a minimal hand-rolled codec implementing THIS interface,
 * with nothing above it changing. That is the entire justification for the indirection; it is not
 * here to support two codecs at once.
 *
 * <p>The interface is deliberately narrow enough that a replacement is a weekend rather than a
 * rewrite: two methods, no message type of its own crossing the boundary, and {@link
 * DecodedQuery#wire()} carrying the original bytes so an implementation can build its response out
 * of the original question instead of being handed a reconstruction.
 */
public interface DnsCodec {

  /**
   * Parses the first {@code length} bytes of {@code wire} into a question.
   *
   * @throws DnsFormatException when the bytes are not a DNS message at all — the caller DROPS the
   *     datagram rather than answering it (see {@link DnsFormatException}). A parseable message
   *     that is merely unservable is not this: it decodes fine and gets an rcode.
   */
  DecodedQuery decode(byte[] wire, int length) throws DnsFormatException;

  /**
   * Encodes the response to {@code query}, truncating with TC=1 when it would exceed {@code
   * maxLength}.
   *
   * <p>{@code maxLength} is the caller's budget and differs by transport: over UDP it is min(the
   * client's advertised EDNS0 payload size, 1232), or 512 when the query carried no OPT record;
   * over TCP it is 65535 and truncation effectively never fires. Truncation is not an error — TC=1
   * is the instruction that makes a resolver retry over TCP, which is why an authoritative server
   * must speak both.
   *
   * <p>Every response sets AA per {@link ResolutionResult#authoritative()} and RA=0 always: this
   * server offers recursion to nobody, and a stray RA=1 is an invitation.
   */
  byte[] encode(DecodedQuery query, ResolutionResult result, int maxLength);
}
