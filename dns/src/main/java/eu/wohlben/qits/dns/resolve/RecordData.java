package eu.wohlben.qits.dns.resolve;

/**
 * One record as it appears on the wire.
 *
 * <p>{@code owner} is the FQDN the answer carries, and for a WILDCARD MATCH that is the QUERIED
 * name — never a literal {@code *}. A wildcard is expanded, not echoed: resolvers reject answers
 * whose owner name does not match the question, so a record served straight out of the {@code *}
 * row would be discarded by every client that asked for it. This is the single most load-bearing
 * sentence in this type, and it is why {@code owner} is a field here rather than something the
 * codec derives from the question.
 *
 * <p>{@code soa} is non-null exactly when {@code type == SOA}; {@code value} carries the payload for
 * every other type (an IPv4 or IPv6 literal for A/AAAA, a hostname for CNAME and NS). The
 * alternative — a sealed hierarchy per type — buys type-safety over a set of five that is not
 * growing, and costs the wire layer a visitor for what is a two-branch switch.
 *
 * <p>{@code ttl} is already resolved: the record's own TTL if it had one, the zone's default
 * otherwise. Nothing downstream consults configuration again.
 */
public record RecordData(String owner, WireType type, int ttl, String value, SoaData soa) {

  /** A record of any type but SOA. */
  public static RecordData of(String owner, WireType type, int ttl, String value) {
    return new RecordData(owner, type, ttl, value, null);
  }

  /** The synthesized SOA, which carries its fields in {@link SoaData} rather than in a string. */
  public static RecordData soa(String owner, int ttl, SoaData soa) {
    return new RecordData(owner, WireType.SOA, ttl, null, soa);
  }
}
