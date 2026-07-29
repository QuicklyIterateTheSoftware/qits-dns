package eu.wohlben.qits.dns.persistence;

import eu.wohlben.qits.dns.entity.DnsRecord;
import eu.wohlben.qits.dns.entity.DnsRecordType;
import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Panache DAO for {@link DnsRecord} (keyed by its String UUID row id). */
@ApplicationScoped
public class DnsRecordRepository implements PanacheRepositoryBase<DnsRecord, String> {

  /**
   * Every record of one zone, ordered so a snapshot rebuild produces the same answer lists run to
   * run. This is the snapshot builder's read; it is deliberately per-zone rather than one global
   * query, so the builder's shape does not change the day a zone count makes that worth batching.
   */
  public List<DnsRecord> listByZoneId(String zoneId) {
    return list("zoneId = ?1 order by name, type, value", zoneId);
  }

  /** The records configured at one name within a zone, across all types. */
  public List<DnsRecord> listByZoneIdAndName(String zoneId, String name) {
    return list("zoneId = ?1 and name = ?2 order by type, value", zoneId, name);
  }

  /**
   * Deletes every record of a zone and answers how many went. Zone deletion needs this because the
   * SQL foreign key would otherwise refuse — the relation is not in the object model, so nothing
   * cascades on its own.
   */
  public long deleteByZoneId(String zoneId) {
    return delete("zoneId", zoneId);
  }

  /**
   * Deletes every row of one {@code (zone, name, type)} — the first half of the replace-by-set
   * verb.
   *
   * <p>A bulk delete rather than a loop over managed entities, and that is what makes the replace
   * safe: Hibernate orders a flush INSERTS BEFORE DELETES, so persisting the new rows and letting
   * the old ones be removed at commit would violate {@code uq_dns_record} whenever a value survives
   * the replace — the overwhelmingly common case for an idempotent re-deploy. This executes as its
   * own statement at once, so by the time the new rows are persisted the old ones are gone.
   */
  public long deleteByZoneIdNameAndType(String zoneId, String name, DnsRecordType type) {
    return delete("zoneId = ?1 and name = ?2 and type = ?3", zoneId, name, type);
  }
}
