package eu.wohlben.qits.dns.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.entity.DnsZone;
import eu.wohlben.qits.dns.error.BadRequestException;
import eu.wohlben.qits.dns.error.ConflictException;
import eu.wohlben.qits.dns.error.NotFoundException;
import io.quarkus.test.junit.QuarkusTest;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Zone CRUD against the real schema — including the rules that need existing rows to fire. */
@QuarkusTest
public class DnsZoneServiceTest extends DnsPersistenceTestSupport {

  @Test
  public void createsAZoneAtSerialOne() {
    DnsZone zone = zones.create("qits-dev.eu");

    assertNotNull(zone.id);
    assertEquals("qits-dev.eu", zone.fqdn);
    assertEquals(1L, zone.serial);
    assertNotNull(zone.createdAt);
    assertEquals(zone.createdAt, zone.updatedAt);
  }

  @Test
  public void rejectsAMalformedFqdnBeforeItReachesTheDatabase() {
    assertThrows(BadRequestException.class, () -> zones.create("eu"));
    assertThrows(BadRequestException.class, () -> zones.create("QITS-dev.eu"));
    assertTrue(zones.list().isEmpty());
  }

  @Test
  public void rejectsADuplicateOrOverlappingZoneInCodeRatherThanAtTheIndex() {
    // The overlap rules are checked here, not caught from a PersistenceException: the unique index
    // cannot express the suffix rule at all, and its message names a constraint rather than a fix.
    zones.create("qits-dev.eu");

    assertThrows(ConflictException.class, () -> zones.create("qits-dev.eu"));
    assertThrows(ConflictException.class, () -> zones.create("a.qits-dev.eu"));
    assertEquals(1, zones.list().size());
  }

  @Test
  public void listsZonesAndReadsOne() {
    DnsZone first = zones.create("a-zone.eu");
    zones.create("b-zone.eu");

    assertEquals(
        List.of("a-zone.eu", "b-zone.eu"), zones.list().stream().map(z -> z.fqdn).toList());
    assertEquals("a-zone.eu", zones.get(first.id).fqdn);
  }

  @Test
  public void readingOrDeletingAnUnknownZoneIsNotFound() {
    assertThrows(NotFoundException.class, () -> zones.get("nope"));
    assertThrows(NotFoundException.class, () -> zones.recordsOf("nope"));
    assertThrows(NotFoundException.class, () -> zones.delete("nope"));
  }

  @Test
  public void readsAZonesRecordsInOrder() {
    DnsZone zone = zones.create("qits-dev.eu");
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.2", null);
    records.create(zone.id, "@", DnsRecordType.A, "10.0.0.1", null);

    assertEquals(List.of("@", "feature"), zones.recordsOf(zone.id).stream().map(r -> r.name)
        .toList());
  }

  @Test
  public void deletingAZoneTakesItsRecordsWithIt() {
    // The foreign key is real and nothing cascades in the object model, so a zone delete that
    // forgot its records would be refused by the database rather than leaving orphans.
    DnsZone zone = zones.create("qits-dev.eu");
    records.create(zone.id, "@", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "*", DnsRecordType.CNAME, "app.qits.eu", null);

    zones.delete(zone.id);

    assertTrue(zones.list().isEmpty());
    assertEquals(0, recordRows.count());
  }

  @Test
  public void deletingAZoneLeavesAnotherZonesRecordsAlone() {
    DnsZone doomed = zones.create("doomed.eu");
    DnsZone kept = zones.create("kept.eu");
    records.create(doomed.id, "@", DnsRecordType.A, "10.0.0.1", null);
    records.create(kept.id, "@", DnsRecordType.A, "10.0.0.2", null);

    zones.delete(doomed.id);

    assertEquals(1, zones.recordsOf(kept.id).size());
  }
}
