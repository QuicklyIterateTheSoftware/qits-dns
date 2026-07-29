package eu.wohlben.qits.dns.wire;

import eu.wohlben.qits.dns.resolve.DnsResolver;
import eu.wohlben.qits.dns.resolve.ResolutionResult;
import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.datagram.DatagramPacket;
import io.vertx.core.datagram.DatagramSocket;
import io.vertx.core.net.NetServer;
import io.vertx.core.net.NetSocket;
import io.vertx.core.parsetools.RecordParser;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The two sockets qits-dns answers on: a UDP {@link DatagramSocket} and a TCP {@link NetServer},
 * both on {@code qits.dns.host}:{@code qits.dns.port}, both feeding the same decode/resolve/encode
 * pipeline.
 *
 * <p><b>This class holds the only attacker-reachable parse in the repo.</b> Everything else here
 * speaks HTTP behind a gateway; this speaks UDP to whoever sends a packet. Three consequences are
 * written into the code below rather than left to the reader:
 *
 * <ul>
 *   <li><b>Bytes that do not parse are DROPPED and counted, never answered.</b> A FORMERR to
 *       garbage is an amplifier: the attacker forges a victim's source address, spends a few bytes,
 *       and we pay the reply's bandwidth on the victim's behalf. FORMERR exists for a message we
 *       understood well enough to know was malformed (QDCOUNT != 1) and that message parsed.
 *   <li><b>The socket survives everything.</b> A handler that throws must not take the listener
 *       with it — a nameserver that can be silenced by one packet is a nameserver that will be.
 *   <li><b>TCP is not optional.</b> Truncation tells a resolver to retry over TCP, so an
 *       authoritative server that speaks only UDP fails every answer that does not fit — and some
 *       resolvers probe TCP outright.
 * </ul>
 *
 * <p>This class names no {@code org.xbill.DNS} type, and that is deliberate: the codec choice lives
 * behind {@link DnsCodec}, and a listener that reached for {@code Opcode.QUERY} for the sake of one
 * integer would quietly make the seam decorative. The two wire constants it needs are spelled here
 * against their RFC.
 *
 * <p>Policy that does NOT live here: what a question resolves to. The one exception is the pair of
 * outcomes that are statements about the MESSAGE rather than about a name — a non-QUERY opcode and
 * QDCOUNT != 1 — which are constructed here because {@link DnsResolver} is only ever asked about an
 * actual question. Everything else, ANY and AXFR included, is the resolver's call (see {@link
 * DnsResolver}'s note on why {@code qtype} crosses that seam as a number).
 */
@ApplicationScoped
public class DnsWireServer {

  private static final Logger LOG = Logger.getLogger(DnsWireServer.class);

  /** Opcode 0, QUERY (RFC 1035 §4.1.1). Anything else gets NOTIMP. */
  private static final int OPCODE_QUERY = 0;

  /**
   * RFC 1035 §4.2.1: a UDP message without EDNS0 may not exceed 512 bytes. Also the FLOOR for an
   * EDNS0 budget — a client advertising less than 512 is asking for something no DNS implementation
   * has ever had to honour, and clamping up is friendlier than emitting a header-only response.
   */
  static final int UDP_BUDGET_WITHOUT_EDNS = 512;

  /**
   * The ceiling on an EDNS0 UDP budget, whatever the client advertised. 1232 is 1280 (the IPv6
   * minimum MTU) less 48 bytes of IPv6 and UDP headers, and it is the number the DNS Flag Day 2020
   * consensus settled on: above it, responses fragment, and fragmented UDP is both lossy and a
   * cache-poisoning surface.
   */
  static final int UDP_BUDGET_CEILING = 1232;

  /**
   * TCP's budget: the framing's own 16-bit length prefix is the only limit, so truncation
   * effectively never fires there. That is the point — TC=1 over UDP means "ask again over TCP",
   * and the answer has to actually fit when it does.
   */
  static final int TCP_BUDGET = 65535;

  /**
   * How long a bind may take before boot is declared failed. Generous; a bind is instant or never.
   */
  private static final long BIND_TIMEOUT_SECONDS = 10;

  @Inject Vertx vertx;

  @Inject DnsCodec codec;

  /**
   * The resolution contract, looked up rather than injected directly — and the one thing in this
   * class that is shaped by the repo's build order rather than by DNS.
   *
   * <p>{@link DnsResolver}'s sole implementation lives in the {@code dns} module. A direct
   * {@code @Inject DnsResolver} makes {@code service}'s Quarkus augmentation fail at BUILD time
   * when that implementation is absent, which is a virtue in a finished repo and a hard coupling
   * while the two modules are being written in parallel: the wire layer would stop compiling on the
   * strength of a class it does not own and cannot write. {@link Instance} moves the same check
   * from augmentation to {@link #onStart}, where a missing implementation is a loud boot failure
   * rather than a silent one — and {@code DnsPackagedSurfaceIT} boots the packaged binary, so the
   * property "a shipped qits-dns has a resolver" is still enforced by the gate, one phase later.
   *
   * <p>Resolved exactly once, at startup. Never per query: {@link Instance#get()} is a lookup, and
   * doing it on the event loop for every datagram would put a container lookup in the hot path of
   * the one code path that must not touch anything slow.
   */
  @Inject Instance<DnsResolver> resolvers;

  @ConfigProperty(name = "qits.dns.host")
  String host;

  @ConfigProperty(name = "qits.dns.port")
  int port;

  private DatagramSocket udp;
  private NetServer tcp;
  private volatile int boundPort;

  /** Volatile because it is written on the startup thread and read on every event loop. */
  private volatile DnsResolver resolver;

  /**
   * Unparseable messages seen since boot. Counted rather than merely dropped because the count is
   * the only evidence this path leaves — the response that would have shown up in a log is exactly
   * the response we are refusing to send — and a rate climbing here is what a reflection attempt
   * looks like from the inside.
   */
  private final AtomicLong dropped = new AtomicLong();

  void onStart(@Observes StartupEvent event) {
    if (resolvers.isUnsatisfied()) {
      // Before either socket is bound: a nameserver that cannot resolve has nothing to say, and
      // binding first would mean answering the internet with a NullPointerException per packet.
      throw new IllegalStateException(
          "no DnsResolver implementation is on the classpath; qits-dns cannot answer anything");
    }
    resolver = resolvers.get();

    // UDP first, and TCP onto whatever port UDP ended up with. With qits.dns.port=0 (the test
    // default) somebody has to pick the number and the other has to follow, and it must be the same
    // number on both protocols or a truncation retry lands nowhere. The residual risk is real but
    // tiny: the UDP port the OS handed out could be taken on TCP by an unrelated process, which
    // shows up as a loud bind failure here rather than as a half-open server.
    udp = vertx.createDatagramSocket();
    udp.handler(this::onDatagram);
    udp.exceptionHandler(t -> LOG.debugf(t, "datagram socket reported an error; still listening"));
    await(udp.listen(port, host), "UDP");
    boundPort = udp.localAddress().port();

    tcp = vertx.createNetServer();
    tcp.connectHandler(this::onConnect);
    tcp.exceptionHandler(t -> LOG.debugf(t, "TCP listener reported an error; still listening"));
    await(tcp.listen(boundPort, host), "TCP");

    LOG.infof("qits-dns answering on %s:%s over UDP and TCP", host, Integer.valueOf(boundPort));
  }

  /**
   * The port both listeners are actually on, which is not {@code qits.dns.port} when that is 0. The
   * suite binds ephemeral ports so a run never fights a developer's own server for 8053, and this
   * is how it finds them.
   */
  public int boundPort() {
    return boundPort;
  }

  /** Unparseable messages dropped since boot. See {@link #dropped}. */
  public long droppedMessages() {
    return dropped.get();
  }

  // --- UDP ---------------------------------------------------------------------------------------

  private void onDatagram(DatagramPacket packet) {
    byte[] response = respond(packet.data().getBytes(), false);
    if (response == null) {
      return;
    }
    udp.send(Buffer.buffer(response), packet.sender().port(), packet.sender().host())
        .onFailure(t -> LOG.debugf(t, "could not answer %s", packet.sender()));
  }

  // --- TCP ---------------------------------------------------------------------------------------

  /**
   * RFC 1035 §4.2.2 framing: every message on a TCP connection is preceded by its own length as a
   * two-byte big-endian prefix, in both directions. {@link RecordParser} alternates between the two
   * fixed sizes — 2 for the prefix, then whatever the prefix said — which is exactly the shape it
   * was built for.
   *
   * <p>A connection may carry SEVERAL queries, which is why the parser flips back to 2 rather than
   * the socket being closed after one answer. Resolvers reuse a connection (RFC 7766), and a server
   * that answers once and hangs up turns every follow-up into a fresh handshake.
   *
   * <p>The handler keeps its state in a plain field with no synchronization: vert.x runs every
   * callback for one socket on one event loop, so there is nothing here two threads can race over.
   */
  private void onConnect(NetSocket socket) {
    RecordParser parser = RecordParser.newFixed(2);
    parser.handler(
        new Handler<Buffer>() {
          private boolean expectingLength = true;

          @Override
          public void handle(Buffer buffer) {
            if (expectingLength) {
              int length = buffer.getUnsignedShort(0);
              if (length == 0) {
                // A zero-length message is not a message. Nothing sensible follows it, so stop.
                socket.close();
                return;
              }
              expectingLength = false;
              parser.fixedSizeMode(length);
              return;
            }
            expectingLength = true;
            parser.fixedSizeMode(2);
            byte[] response = respond(buffer.getBytes(), true);
            if (response == null) {
              // Dropped, and on TCP that means closed. The amplification argument does not apply to
              // a stream a peer had to complete a handshake for, but a peer sending bytes we cannot
              // parse has told us nothing about where its next length prefix begins — so the frame
              // boundary is lost and reading on would be guessing.
              socket.close();
              return;
            }
            socket.write(framed(response)).onFailure(t -> socket.close());
          }
        });
    socket.handler(parser);
    socket.exceptionHandler(
        t -> {
          // A peer that vanishes mid-frame is ordinary internet weather, not an incident.
          LOG.debugf(t, "TCP peer %s went away", socket.remoteAddress());
          socket.close();
        });
  }

  private static Buffer framed(byte[] response) {
    Buffer buffer = Buffer.buffer(2 + response.length);
    buffer.appendUnsignedShort(response.length);
    buffer.appendBytes(response);
    return buffer;
  }

  // --- the shared exchange -----------------------------------------------------------------------

  /**
   * One request to one response, or null when the bytes are dropped.
   *
   * @param overTcp which cap applies. TCP's is fixed; UDP's depends on the EDNS0 OPT and so cannot
   *     be known until the message has been decoded, which is why this is a flag rather than a
   *     size.
   */
  private byte[] respond(byte[] request, boolean overTcp) {
    DecodedQuery query;
    try {
      query = codec.decode(request, request.length);
    } catch (DnsFormatException e) {
      // Debug, not warn. On a public socket this is background noise — scanners, stale caches,
      // truncated retransmissions — and logging it at a level anyone watches would make the log
      // itself an amplification target. The counter is what carries the signal.
      LOG.debugf(
          e,
          "dropped %s unparseable bytes (%s dropped so far)",
          Integer.valueOf(request.length),
          Long.valueOf(dropped.incrementAndGet()));
      return null;
    }
    return codec.encode(query, resolve(query), overTcp ? TCP_BUDGET : udpBudget(query));
  }

  /**
   * The two message-level verdicts, then the resolver for everything else.
   *
   * <p>Order matters: opcode is checked first because QDCOUNT is meaningless in an UPDATE, whose
   * second section count means something else entirely.
   */
  private ResolutionResult resolve(DecodedQuery query) {
    if (query.opcode() != OPCODE_QUERY) {
      return ResolutionResult.notImplemented();
    }
    if (query.qdcount() != 1) {
      return ResolutionResult.formatError();
    }
    return resolver.resolve(query.qname(), query.qtype());
  }

  /**
   * min(what the client advertised, 1232), floored at 512 — and a flat 512 when the query carried
   * no OPT at all, which is the pre-EDNS0 limit and the only size such a client is guaranteed to
   * accept. Anything larger is not "a bigger answer", it is an answer that arrives fragmented or
   * not at all.
   */
  private static int udpBudget(DecodedQuery query) {
    OptionalInt advertised = query.ednsPayloadSize();
    return advertised.isEmpty()
        ? UDP_BUDGET_WITHOUT_EDNS
        : Math.clamp(advertised.getAsInt(), UDP_BUDGET_WITHOUT_EDNS, UDP_BUDGET_CEILING);
  }

  // --- lifecycle ---------------------------------------------------------------------------------

  /**
   * Waits for a bind, because vert.x's {@code listen} is asynchronous and a startup observer that
   * returns before it completes hands the application a half-open server — which in a test shows up
   * as a flaky connection refused, and in a deployment as a health check that passed too early. A
   * failure here is rethrown so boot fails loudly: a DNS server that could not take its port has
   * nothing to offer and must not pretend otherwise.
   */
  private <T> void await(Future<T> listen, String protocol) {
    try {
      listen.toCompletionStage().toCompletableFuture().get(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted binding the DNS " + protocol + " listener", e);
    } catch (Exception e) {
      throw new IllegalStateException(
          "could not bind the DNS " + protocol + " listener on " + host + ":" + port, e);
    }
  }

  @PreDestroy
  void close() {
    if (tcp != null) {
      tcp.close();
    }
    if (udp != null) {
      udp.close();
    }
  }
}
