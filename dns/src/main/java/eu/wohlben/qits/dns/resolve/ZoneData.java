package eu.wohlben.qits.dns.resolve;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * One zone as the hot path sees it: the apex name, the serial its SOA reports, and its records
 * already grouped by their stored name.
 *
 * <p>{@code byName} is keyed by the STORED name — {@code @}, {@code app.feature}, {@code *},
 * {@code *.feature}, {@code *.*} — because that is what the matching table looks names up by. The
 * table tries at most three keys for any query, so a map is the whole index the resolver needs and
 * there is nothing to scan.
 *
 * <p>{@code soa} and {@code nsNames} are EMPTY when {@code qits.dns.ns-names} or {@code
 * qits.dns.hostmaster} is blank. That is not a degraded state to guard against — it is the shipped
 * default, because this repo cannot know its own public names and a default it invented would
 * resolve while being wrong. Synthesis off means SOA and NS queries have nothing to answer with;
 * A/AAAA/CNAME are unaffected, which is what keeps dev and the test suite working with no
 * configuration at all.
 *
 * <p>{@code defaultTtl} is carried even though {@link StoredRecord#ttl} is already defaulted,
 * because the synthesized SOA and NS records need a TTL of their own and they came from no row.
 */
public record ZoneData(
    String fqdn,
    long serial,
    Map<String, List<StoredRecord>> byName,
    Optional<SoaData> soa,
    List<String> nsNames,
    int defaultTtl) {}
