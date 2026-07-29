package eu.wohlben.qits.dns.persistence;

import eu.wohlben.qits.dns.entity.DnsZone;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;

/** Panache DAO for {@link DnsZone} (keyed by its String UUID row id). */
@ApplicationScoped
public class DnsZoneRepository implements PanacheRepositoryBase<DnsZone, String> {

  /**
   * The zone with this exact fqdn, if configured. The argument is expected already lowercased and
   * without a trailing dot, which is how the column is stored — this is a lookup, not a
   * normalisation step.
   */
  public Optional<DnsZone> findByFqdn(String fqdn) {
    return find("fqdn", fqdn).firstResultOptional();
  }

  /**
   * Every zone, ordered by fqdn. The order is for the API's list surface and for reproducible
   * snapshot builds; the resolver's own longest-suffix lookup does not depend on it.
   */
  public List<DnsZone> listAllOrdered() {
    return list("order by fqdn");
  }
}
