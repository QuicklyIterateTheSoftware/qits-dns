package eu.wohlben.qits.dns.control;

import eu.wohlben.qits.dns.entity.DnsRecord;
import eu.wohlben.qits.dns.entity.DnsZone;
import eu.wohlben.qits.dns.error.NotFoundException;
import eu.wohlben.qits.dns.persistence.DnsRecordRepository;
import eu.wohlben.qits.dns.persistence.DnsZoneRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Zone lifecycle: create, list, read, delete. The operations §6's table needs, with the HTTP left
 * out entirely — no status codes, no {@code Response}, no DTOs. The JAX-RS layer is a different
 * module's and it calls these; keeping the rules on this side is what lets the API surface be
 * rewritten (or gain a second one) without the rules moving.
 *
 * <p>Validation is {@link DnsNames}' and only {@link DnsNames}'. In particular the overlap check
 * reads the existing zones and decides in code rather than letting the unique index decide: a
 * {@code PersistenceException} caught and turned into a 409 gives the caller "constraint
 * UQ_DNS_ZONE violated", which names a database object rather than the mistake, and it cannot say
 * anything at all about the suffix/prefix rule the index does not enforce.
 *
 * <p><b>No method here rebuilds the snapshot.</b> A rebuild belongs after the commit — this class
 * cannot see its own commit, and a rebuild from inside the transaction would publish a write that
 * may still roll back. Each mutating method says so in its own Javadoc; the wiring is wave 2's, at
 * the boundary that owns the transaction.
 */
@ApplicationScoped
public class DnsZoneService {

  @Inject DnsZoneRepository zones;
  @Inject DnsRecordRepository records;

  /**
   * Creates a zone at serial 1.
   *
   * <p>Serial 1 rather than 0 or a timestamp: it is a plain bump-on-write counter that only ever
   * appears inside an SOA answer, nothing compares it across servers because there are no
   * secondaries, and starting at 1 means "never written" and "written once" are distinguishable.
   *
   * <p><b>The caller owes a {@link ZoneSnapshotHolder#rebuild()} once this transaction commits.</b>
   *
   * @throws eu.wohlben.qits.dns.error.BadRequestException if the fqdn is malformed
   * @throws eu.wohlben.qits.dns.error.ConflictException if it exists or overlaps a zone that does
   */
  @Transactional
  public DnsZone create(String fqdn) {
    String normalised = DnsNames.requireZoneFqdn(fqdn);
    List<String> existing = new ArrayList<>();
    for (DnsZone zone : zones.listAllOrdered()) {
      existing.add(zone.fqdn);
    }
    DnsNames.requireZoneAvailable(normalised, existing);

    Instant now = Instant.now();
    DnsZone zone = new DnsZone();
    zone.id = UUID.randomUUID().toString();
    zone.fqdn = normalised;
    zone.serial = 1L;
    zone.createdAt = now;
    zone.updatedAt = now;
    zones.persist(zone);
    return zone;
  }

  /** Every zone, ordered by fqdn. */
  @Transactional
  public List<DnsZone> list() {
    return zones.listAllOrdered();
  }

  /**
   * One zone.
   *
   * @throws NotFoundException if no zone has that id
   */
  @Transactional
  public DnsZone get(String id) {
    return zones
        .findByIdOptional(id)
        .orElseThrow(() -> new NotFoundException("No DNS zone with id '" + id + "'"));
  }

  /**
   * One zone's records, ordered.
   *
   * @throws NotFoundException if no zone has that id
   */
  @Transactional
  public List<DnsRecord> recordsOf(String zoneId) {
    get(zoneId);
    return records.listByZoneId(zoneId);
  }

  /**
   * Deletes a zone and every record under it.
   *
   * <p>The records go explicitly: the foreign key lives in SQL and not in the object model, so
   * nothing cascades on its own and a bare zone delete would be refused by the database.
   *
   * <p><b>The caller owes a {@link ZoneSnapshotHolder#rebuild()} once this transaction commits.</b>
   *
   * @throws NotFoundException if no zone has that id
   */
  @Transactional
  public void delete(String id) {
    DnsZone zone = get(id);
    records.deleteByZoneId(zone.id);
    zones.delete(zone);
  }

  /**
   * Bumps a zone's serial and its {@code updatedAt}, for a write that happened somewhere in it.
   *
   * <p>Static, and taking a MANAGED entity: it is a mutation of an object the caller already holds
   * inside its own transaction, so Hibernate's dirty checking writes it at that transaction's
   * flush. Making it a transactional method of its own would only add a second bracket around a
   * field assignment — and, worse, invite a caller to bump a serial in a transaction the write it
   * belongs to is not in.
   */
  static void bumpSerial(DnsZone zone) {
    zone.serial += 1;
    zone.updatedAt = Instant.now();
  }
}
