package eu.wohlben.qits.dns.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.entity.DnsZone;
import eu.wohlben.qits.dns.resolve.StoredRecord;
import eu.wohlben.qits.dns.resolve.ZoneData;
import eu.wohlben.qits.dns.resolve.ZoneSnapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The builder against a real database, with SOA/NS synthesis OFF — the shipped default and the
 * state the rest of this module's suite runs in.
 *
 * <p>{@code @QuarkusTest} here and nowhere in the resolver suite: this is the one class whose whole
 * job is turning rows into the read model, so rows are exactly what it needs. The datasource is the
 * in-memory H2 the module's test properties configure; no docker is involved anywhere.
 */
@QuarkusTest
public class ZoneSnapshotBuilderTest extends DnsPersistenceTestSupport {

  @Inject ZoneSnapshotBuilder builder;

  @Test
  public void aRecordWithNoTtlOfItsOwnTakesTheConfiguredDefault() {
    DnsZone zone = zones.create("ttl.eu");
    records.create(zone.id, "default", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "pinned", DnsRecordType.A, "10.0.0.2", 5);

    ZoneData data = zoneOf(builder.build(), "ttl.eu");
    assertEquals(60, data.byName().get("default").get(0).ttl());
    assertEquals(5, data.byName().get("pinned").get(0).ttl());
    assertEquals(60, data.defaultTtl());
  }

  @Test
  public void rowsAreGroupedByTheirStoredName() {
    DnsZone zone = zones.create("grouped.eu");
    records.create(zone.id, "@", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.2", null);
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.3", null);
    records.create(zone.id, "feature", DnsRecordType.AAAA, "2001:db8::1", null);
    records.create(zone.id, "*", DnsRecordType.CNAME, "app.qits.eu", null);

    ZoneData data = zoneOf(builder.build(), "grouped.eu");
    assertEquals(3, data.byName().size());
    assertEquals(1, data.byName().get("@").size());
    assertEquals(3, data.byName().get("feature").size());
    // The wildcard is an ordinary row under its stored, unexpanded name.
    assertEquals(DnsRecordType.CNAME, data.byName().get("*").get(0).type());
    assertEquals("*", data.byName().get("*").get(0).name());
  }

  @Test
  public void theZonesSerialIsCarriedIntoTheSnapshot() {
    DnsZone zone = zones.create("serial.eu");
    records.create(zone.id, "@", DnsRecordType.A, "10.0.0.1", null);
    records.create(zone.id, "@", DnsRecordType.A, "10.0.0.2", null);

    assertEquals(3L, zoneOf(builder.build(), "serial.eu").serial());
  }

  @Test
  public void everyZoneIsInTheSnapshotAndFoundBySuffix() {
    zones.create("one.eu");
    zones.create("two.eu");

    ZoneSnapshot snapshot = builder.build();
    assertEquals("one.eu", snapshot.zoneFor("x.one.eu").orElseThrow().fqdn());
    assertEquals("two.eu", snapshot.zoneFor("two.eu").orElseThrow().fqdn());
    assertTrue(snapshot.zoneFor("three.eu").isEmpty());
  }

  @Test
  public void synthesisIsOffAndTheZoneCarriesNeitherSoaNorNsNames() {
    zones.create("bare.eu");

    ZoneData data = zoneOf(builder.build(), "bare.eu");
    assertFalse(builder.synthesisEnabled());
    assertTrue(data.soa().isEmpty());
    assertTrue(data.nsNames().isEmpty());
  }

  @Test
  public void aRebuildSeesWhateverIsCommittedAtTheTimeItRuns() {
    DnsZone zone = zones.create("rebuilt.eu");
    assertTrue(zoneOf(builder.build(), "rebuilt.eu").byName().isEmpty());

    records.create(zone.id, "@", DnsRecordType.A, "10.0.0.1", null);
    List<StoredRecord> rows = zoneOf(builder.build(), "rebuilt.eu").byName().get("@");
    assertEquals(1, rows.size());
    assertEquals("10.0.0.1", rows.get(0).value());
  }

  private static ZoneData zoneOf(ZoneSnapshot snapshot, String fqdn) {
    return snapshot.zoneFor(fqdn).orElseThrow();
  }
}
