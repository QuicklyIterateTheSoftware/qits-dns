package eu.wohlben.qits.dns.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.entity.DnsRecord;
import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.entity.DnsZone;
import eu.wohlben.qits.dns.error.BadRequestException;
import eu.wohlben.qits.dns.error.ConflictException;
import eu.wohlben.qits.dns.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Record CRUD, the replace-by-set verb, and the serial bump every mutation owes its zone. */
@QuarkusTest
public class DnsRecordServiceTest extends DnsPersistenceTestSupport {

  private DnsZone zone;

  @BeforeEach
  void createZone() {
    zone = zones.create("qits-dev.eu");
  }

  // --- create -----------------------------------------------------------------------------------

  @Test
  public void createsARecordAndBumpsTheZoneSerial() {
    DnsRecord record = records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);

    assertEquals("feature", record.name);
    assertEquals(DnsRecordType.A, record.type);
    assertEquals("10.0.0.1", record.value);
    assertNull(record.ttl, "no override means the record follows qits.dns.ttl-seconds");
    assertEquals(2L, zones.get(zone.id).serial);
  }

  @Test
  public void normalisesACnameTargetOnTheWayIn() {
    DnsRecord record = records.create(zone.id, "*", DnsRecordType.CNAME, "app.qits.eu.", null);

    assertEquals("app.qits.eu", record.value, "the trailing dot is stripped, once, on storage");
  }

  @Test
  public void rejectsARecordInAZoneThatDoesNotExist() {
    assertThrows(
        NotFoundException.class,
        () -> records.create("nope", "feature", DnsRecordType.A, "10.0.0.1", null));
  }

  @Test
  public void rejectsMalformedNamesValuesAndTtls() {
    assertThrows(
        BadRequestException.class,
        () -> records.create(zone.id, "a.b.c", DnsRecordType.A, "10.0.0.1", null));
    assertThrows(
        BadRequestException.class,
        () -> records.create(zone.id, "feature", DnsRecordType.A, "not-an-address", null));
    assertThrows(
        BadRequestException.class,
        () -> records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", -1));
    assertTrue(zones.recordsOf(zone.id).isEmpty());
  }

  @Test
  public void rejectsTheCnameConflictsWithARealisableMessage() {
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);

    assertThrows(
        ConflictException.class,
        () -> records.create(zone.id, "@", DnsRecordType.CNAME, "app.qits.eu", null));
    assertThrows(
        ConflictException.class,
        () -> records.create(zone.id, "feature", DnsRecordType.CNAME, "app.qits.eu", null));
    assertThrows(
        ConflictException.class,
        () -> records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null));
  }

  @Test
  public void acceptsSeveralAddressesAndBothFamiliesAtOneName() {
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.2", null);
    records.create(zone.id, "feature", DnsRecordType.AAAA, "2001:db8::1", null);

    assertEquals(3, zones.recordsOf(zone.id).size());
    assertEquals(4L, zones.get(zone.id).serial);
  }

  // --- replace ----------------------------------------------------------------------------------

  @Test
  public void replacesAWholeSetInOneTransaction() {
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.2", null);

    List<DnsRecord> written =
        records.replaceSet(zone.id, "feature", DnsRecordType.A, List.of("10.9.9.9"), 30);

    assertEquals(1, written.size());
    assertEquals(List.of("10.9.9.9"), valuesAt("feature", DnsRecordType.A));
    assertEquals(30, written.get(0).ttl);
  }

  @Test
  public void replacingWithAnOverlappingSetKeepsTheSurvivingValue() {
    // The insert-before-delete hazard: Hibernate flushes INSERTS before DELETES, so a naive
    // implementation violates uq_dns_record exactly when a value survives the replace — which is
    // the common case for an idempotent re-deploy, and therefore the one that must be tested.
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.2", null);

    records.replaceSet(zone.id, "feature", DnsRecordType.A, List.of("10.0.0.1", "10.0.0.3"), null);

    assertEquals(List.of("10.0.0.1", "10.0.0.3"), valuesAt("feature", DnsRecordType.A));
  }

  @Test
  public void replacingTheIdenticalSetIsANoOpToTheCaller() {
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);

    records.replaceSet(zone.id, "feature", DnsRecordType.A, List.of("10.0.0.1"), null);

    assertEquals(List.of("10.0.0.1"), valuesAt("feature", DnsRecordType.A));
  }

  @Test
  public void replacingOneTypeLeavesTheOtherTypesAtThatNameAlone() {
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "feature", DnsRecordType.AAAA, "2001:db8::1", null);

    records.replaceSet(zone.id, "feature", DnsRecordType.A, List.of("10.0.0.9"), null);

    assertEquals(List.of("10.0.0.9"), valuesAt("feature", DnsRecordType.A));
    assertEquals(List.of("2001:db8::1"), valuesAt("feature", DnsRecordType.AAAA));
  }

  @Test
  public void replacingLeavesOtherNamesAlone() {
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "other", DnsRecordType.A, "10.0.0.2", null);

    records.replaceSet(zone.id, "feature", DnsRecordType.A, List.of("10.0.0.9"), null);

    assertEquals(List.of("10.0.0.2"), valuesAt("other", DnsRecordType.A));
  }

  @Test
  public void replacingIntoAnEmptyNameJustCreates() {
    records.replaceSet(zone.id, "*", DnsRecordType.CNAME, List.of("app.qits.eu"), null);

    assertEquals(List.of("app.qits.eu"), valuesAt("*", DnsRecordType.CNAME));
  }

  @Test
  public void replacingBumpsTheSerialOnce() {
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);
    long before = zones.get(zone.id).serial;

    records.replaceSet(zone.id, "feature", DnsRecordType.A, List.of("10.0.0.2", "10.0.0.3"), null);

    assertEquals(before + 1, zones.get(zone.id).serial, "one write, one serial");
  }

  @Test
  public void rejectsAReplaceThatWouldBreakTheCnameRulesAndChangesNothing() {
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);

    assertThrows(
        ConflictException.class,
        () ->
            records.replaceSet(
                zone.id, "feature", DnsRecordType.CNAME, List.of("app.qits.eu"), null));
    assertThrows(
        BadRequestException.class,
        () -> records.replaceSet(zone.id, "feature", DnsRecordType.A, List.of(), null));

    assertEquals(List.of("10.0.0.1"), valuesAt("feature", DnsRecordType.A));
  }

  @Test
  public void rejectsAReplaceInAZoneThatDoesNotExist() {
    assertThrows(
        NotFoundException.class,
        () -> records.replaceSet("nope", "feature", DnsRecordType.A, List.of("10.0.0.1"), null));
  }

  // --- delete -----------------------------------------------------------------------------------

  @Test
  public void deletesOneRecordAndBumpsTheSerial() {
    DnsRecord kept = records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);
    DnsRecord doomed = records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.2", null);
    long before = zones.get(zone.id).serial;

    records.delete(doomed.id);

    assertEquals(List.of(kept.id), zones.recordsOf(zone.id).stream().map(r -> r.id).toList());
    assertEquals(before + 1, zones.get(zone.id).serial);
  }

  @Test
  public void deletingAnUnknownRecordIsNotFound() {
    assertThrows(NotFoundException.class, () -> records.delete("nope"));
  }

  private List<String> valuesAt(String name, DnsRecordType type) {
    return zones.recordsOf(zone.id).stream()
        .filter(record -> record.name.equals(name) && record.type == type)
        .map(record -> record.value)
        .sorted()
        .toList();
  }
}
