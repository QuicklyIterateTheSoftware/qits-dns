package eu.wohlben.qits.dns.resolve;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The immutable read model the resolver runs against: every zone and every record, already grouped
 * and already TTL-defaulted.
 *
 * <p>Built at boot and rebuilt whole after every mutating API call, then swapped in with one
 * volatile write. That is the arrangement that keeps the UDP event loop off the datasource
 * entirely: a query burst costs zero database load, and no resolution can ever block on a
 * connection pool. Rebuilding the whole thing per write rather than invalidating parts of it is a
 * deliberate trade at this data size — hundreds of rows, not millions — where the simple thing is
 * also the one that cannot leave a stale entry behind.
 *
 * <p>A rebuild reads committed state, so concurrent writers converge on whichever rebuild runs
 * last. An instance is never mutated after {@link #of} returns.
 */
public final class ZoneSnapshot {

  private static final ZoneSnapshot EMPTY = new ZoneSnapshot(Map.of());

  private final Map<String, ZoneData> byFqdn;

  private ZoneSnapshot(Map<String, ZoneData> byFqdn) {
    this.byFqdn = byFqdn;
  }

  /** A snapshot over these zones, keyed by their (lowercase, dot-less) apex names. */
  public static ZoneSnapshot of(Collection<ZoneData> zones) {
    Map<String, ZoneData> map = new LinkedHashMap<>();
    for (ZoneData zone : zones) {
      map.put(zone.fqdn(), zone);
    }
    return new ZoneSnapshot(Map.copyOf(map));
  }

  /**
   * The snapshot a server that has been configured with nothing serves from. Every query against it
   * is out-of-zone and therefore REFUSED, which is the correct behaviour for a nameserver holding
   * no zones — not an error state, and not something boot has to special-case.
   */
  public static ZoneSnapshot empty() {
    return EMPTY;
  }

  /**
   * The longest configured zone that is a suffix of {@code qname}, which is expected already
   * lowercased and without a trailing dot.
   *
   * <p>Longest wins so that delegating both {@code qits-dev.eu} and {@code eu} — which nobody will,
   * but the rule has to be stated — puts a query for {@code x.qits-dev.eu} in the more specific
   * one. The suffix must land on a LABEL BOUNDARY: {@code notqits-dev.eu} is not in zone {@code
   * qits-dev.eu}, and a plain {@code endsWith} would say it is.
   *
   * <p>The scan is over every zone because the zone count is small and a zone lookup happens once
   * per query. If that ever stops being true the fix is to walk the qname's own suffixes instead —
   * at most a handful of map probes — not to index this differently.
   */
  public Optional<ZoneData> zoneFor(String qname) {
    if (qname == null || qname.isEmpty()) {
      return Optional.empty();
    }
    ZoneData best = null;
    for (ZoneData zone : byFqdn.values()) {
      String fqdn = zone.fqdn();
      boolean inZone = qname.equals(fqdn) || qname.endsWith("." + fqdn);
      if (inZone && (best == null || fqdn.length() > best.fqdn().length())) {
        best = zone;
      }
    }
    return Optional.ofNullable(best);
  }

  /** Every zone in this snapshot, in the order {@link #of} received them. */
  public Collection<ZoneData> zones() {
    return List.copyOf(byFqdn.values());
  }
}
