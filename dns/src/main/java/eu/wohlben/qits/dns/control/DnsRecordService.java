package eu.wohlben.qits.dns.control;

import eu.wohlben.qits.dns.control.DnsNames.ExistingRecord;
import eu.wohlben.qits.dns.entity.DnsRecord;
import eu.wohlben.qits.dns.entity.DnsRecordType;
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
 * Record lifecycle: create one, replace a whole {@code (name, type)} set, delete one. Like {@link
 * DnsZoneService} it knows nothing about HTTP, and like it, every mutation bumps the owning zone's
 * serial inside the same transaction — a record change nobody's SOA reflects is a change no
 * secondary and no cache has any way to notice.
 *
 * <p><b>Replace-by-set is the verb this API exists for.</b> An automated deployer re-running the
 * same deployment wants "these are the addresses of this name now" and does not want to discover
 * which of them already existed; a create-only API makes every re-deploy a dance around 409s, and
 * the dance is where a teardown-then-create window comes from. So the swap is one transaction: the
 * old rows are gone and the new ones are there, or neither happened.
 *
 * <p>None of these methods rebuild the snapshot — see {@link DnsZoneService}'s note on why that
 * belongs after the commit, at wave 2's boundary.
 */
@ApplicationScoped
public class DnsRecordService {

  @Inject DnsZoneRepository zones;
  @Inject DnsRecordRepository records;

  /**
   * Adds one record to a zone.
   *
   * <p><b>The caller owes a {@link ZoneSnapshotHolder#rebuild()} once this transaction commits.</b>
   *
   * @throws NotFoundException if no zone has that id
   * @throws eu.wohlben.qits.dns.error.BadRequestException if the name, type, value or ttl is
   *     malformed
   * @throws eu.wohlben.qits.dns.error.ConflictException if the row cannot join the ones already at
   *     that name
   */
  @Transactional
  public DnsRecord create(
      String zoneId, String name, DnsRecordType type, String value, Integer ttl) {
    DnsZone zone = requireZone(zoneId);
    String recordName = DnsNames.requireRecordName(name);
    String recordValue = DnsNames.requireValue(type, value);
    Integer recordTtl = DnsNames.requireTtl(ttl);
    DnsNames.requireAddable(recordName, type, recordValue, existingAt(zoneId, recordName));

    DnsRecord record = insert(zone, recordName, type, recordValue, recordTtl);
    DnsZoneService.bumpSerial(zone);
    return record;
  }

  /**
   * Atomically swaps every row of one {@code (zone, name, type)} for the given values.
   *
   * <p>The rows of OTHER types at the same name are untouched, which is what makes this a
   * replace-by-set rather than a replace-by-name: swapping a name's A records must not silently
   * remove its AAAA records, and a deployer that manages the two families separately is the normal
   * case rather than an odd one.
   *
   * <p><b>The caller owes a {@link ZoneSnapshotHolder#rebuild()} once this transaction commits.</b>
   *
   * @return the rows the name now holds for that type, in the order given
   * @throws NotFoundException if no zone has that id
   * @throws eu.wohlben.qits.dns.error.BadRequestException if the name, type, a value or the ttl is
   *     malformed, or the value list is empty or repeats itself
   * @throws eu.wohlben.qits.dns.error.ConflictException if the resulting set could not coexist with
   *     the rows of other types at that name
   */
  @Transactional
  public List<DnsRecord> replaceSet(
      String zoneId, String name, DnsRecordType type, List<String> values, Integer ttl) {
    DnsZone zone = requireZone(zoneId);
    String recordName = DnsNames.requireRecordName(name);
    Integer recordTtl = DnsNames.requireTtl(ttl);
    List<String> normalised = new ArrayList<>();
    for (String value : values == null ? List.<String>of() : values) {
      normalised.add(DnsNames.requireValue(type, value));
    }
    DnsNames.requireReplaceable(recordName, type, normalised, existingAt(zoneId, recordName));

    records.deleteByZoneIdNameAndType(zoneId, recordName, type);
    List<DnsRecord> written = new ArrayList<>();
    for (String value : normalised) {
      written.add(insert(zone, recordName, type, value, recordTtl));
    }
    DnsZoneService.bumpSerial(zone);
    return written;
  }

  /**
   * Deletes one record by its id.
   *
   * <p><b>The caller owes a {@link ZoneSnapshotHolder#rebuild()} once this transaction commits.</b>
   *
   * @throws NotFoundException if no record has that id
   */
  @Transactional
  public void delete(String recordId) {
    DnsRecord record =
        records
            .findByIdOptional(recordId)
            .orElseThrow(() -> new NotFoundException("No DNS record with id '" + recordId + "'"));
    DnsZone zone = requireZone(record.zoneId);
    records.delete(record);
    DnsZoneService.bumpSerial(zone);
  }

  private DnsRecord insert(
      DnsZone zone, String name, DnsRecordType type, String value, Integer ttl) {
    Instant now = Instant.now();
    DnsRecord record = new DnsRecord();
    record.id = UUID.randomUUID().toString();
    record.zoneId = zone.id;
    record.name = name;
    record.type = type;
    record.value = value;
    record.ttl = ttl;
    record.createdAt = now;
    record.updatedAt = now;
    records.persist(record);
    return record;
  }

  /** The rows already at a name, reduced to what the conflict rules read. */
  private List<ExistingRecord> existingAt(String zoneId, String name) {
    List<ExistingRecord> existing = new ArrayList<>();
    for (DnsRecord record : records.listByZoneIdAndName(zoneId, name)) {
      existing.add(new ExistingRecord(record.type, record.value));
    }
    return existing;
  }

  private DnsZone requireZone(String zoneId) {
    return zones
        .findByIdOptional(zoneId)
        .orElseThrow(() -> new NotFoundException("No DNS zone with id '" + zoneId + "'"));
  }
}
