package eu.wohlben.qits.dns.resolve;

/**
 * The resolution contract: {@code (qname, qtype)} to a response, against whatever snapshot is
 * current. A pure function in the sense that matters — it reads one immutable snapshot and touches
 * nothing else, so the whole rule set is testable without a socket, a database or a clock.
 *
 * <p>{@code qtype} is the NUMERIC wire type rather than a {@link WireType}, and that is the seam's
 * one non-obvious decision. It means ANY (255) and AXFR (252) / IXFR (251) — which have no member
 * in {@link WireType} — are decided HERE, by the same code that decides everything else, instead of
 * being intercepted by whatever parsed the question. The resolution contract is meant to have
 * exactly one implementation and exactly one place a query's fate is sealed; a wire layer that
 * short-circuits "the types it knows are unsupported" is a second, undocumented copy of the policy.
 *
 * <p>{@code qname} arrives lowercased and without a trailing dot. The response echoes the
 * question's ORIGINAL spelling, but that happens in the wire layer, which rebuilds the response
 * from the original question — nothing here has to carry the caller's capitalisation around.
 *
 * <p>Implementations never throw for a query they dislike; every outcome is a {@link
 * ResolutionResult} with an rcode. The only bytes this server does not answer are the ones that did
 * not parse, and that decision belongs to the wire layer, upstream of here.
 */
public interface DnsResolver {

  /** Resolves one question. Never null, never throws for an unservable query. */
  ResolutionResult resolve(String qname, int qtype);
}
