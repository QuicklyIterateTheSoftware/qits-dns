package eu.wohlben.qits.dns.resolve;

import java.util.List;

/**
 * A resolution's whole outcome: the rcode, whether we are authoritative for what we just said, and
 * the two record sections. The wire layer turns this into bytes and adds nothing of its own — every
 * decision, including the ones a naive split would leave to a codec (ANY is refused, AXFR is
 * refused, an unknown qtype is NODATA rather than an error), has already been made by the time one
 * of these exists.
 *
 * <p>{@code authority} carries the zone's SOA on EVERY negative answer, NXDOMAIN and NODATA alike.
 * Without it a resolver has nothing to negative-cache against and comes back for the same
 * nonexistent name on every request it serves — which is how an authoritative server that is
 * technically correct still gets hammered. The factories below are shaped so that constructing a
 * negative answer without an SOA takes deliberate effort.
 *
 * <p>{@code authoritative} is the AA bit. True for anything decided out of one of our zones — an
 * answer, an NXDOMAIN, a NODATA. False for {@link #refused()}, {@link #notImplemented()} and {@link
 * #formatError()}, which are statements about the request rather than about a zone we hold.
 *
 * <p>Open where the plan is: when SOA synthesis is off ({@code qits.dns.ns-names} or {@code
 * qits.dns.hostmaster} blank) a zone has no SOA to put in authority, and the negative factories
 * below have nothing to be handed. The resolver decides what that means — the plausible reading is
 * a negative answer with an empty authority section, since the alternative is refusing to say a
 * name does not exist merely because nobody configured a hostmaster address.
 */
public record ResolutionResult(
    ResponseCode rcode,
    boolean authoritative,
    List<RecordData> answers,
    List<RecordData> authority) {

  /**
   * Out of our zones, or a query type this server has a policy against (ANY, AXFR, IXFR). The
   * smallest response there is, which is exactly what a socket on the open internet should send to
   * anything it will not serve.
   */
  public static ResolutionResult refused() {
    return new ResolutionResult(ResponseCode.REFUSED, false, List.of(), List.of());
  }

  /** A parseable message whose opcode is not QUERY — UPDATE, NOTIFY, and the rest. */
  public static ResolutionResult notImplemented() {
    return new ResolutionResult(ResponseCode.NOTIMP, false, List.of(), List.of());
  }

  /**
   * A parseable message that is not a single-question query (QDCOUNT != 1). Note the asymmetry with
   * bytes that do not parse at all: those are DROPPED, never answered, because answering garbage is
   * free amplification surface. FORMERR is for a message we did understand well enough to know it
   * was malformed.
   */
  public static ResolutionResult formatError() {
    return new ResolutionResult(ResponseCode.FORMERR, false, List.of(), List.of());
  }

  /** A positive answer out of one of our zones. */
  public static ResolutionResult answer(List<RecordData> answers) {
    return new ResolutionResult(ResponseCode.NOERROR, true, List.copyOf(answers), List.of());
  }

  /**
   * The name exists but has no records of the asked type — NOERROR with an empty answer section and
   * the SOA in authority. Also the answer for a name that is merely a prefix of one that has
   * records ({@code x} when only {@code y.x} is configured): NXDOMAIN there would be
   * negative-cached for the whole subtree and would poison {@code y.x}.
   */
  public static ResolutionResult noData(RecordData soa) {
    return new ResolutionResult(ResponseCode.NOERROR, true, List.of(), List.of(soa));
  }

  /** The name does not exist in a zone we are authoritative for. */
  public static ResolutionResult nxDomain(RecordData soa) {
    return new ResolutionResult(ResponseCode.NXDOMAIN, true, List.of(), List.of(soa));
  }
}
