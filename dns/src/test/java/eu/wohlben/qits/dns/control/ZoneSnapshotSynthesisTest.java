package eu.wohlben.qits.dns.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.entity.DnsZone;
import eu.wohlben.qits.dns.resolve.SoaData;
import eu.wohlben.qits.dns.resolve.ZoneData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The builder with {@code qits.dns.ns-names} and {@code qits.dns.hostmaster} set — a real
 * delegation, and the only configuration in which this server answers SOA or NS at all.
 *
 * <p>A whole test profile (and therefore a second boot) rather than a setter, because the two keys
 * are read through {@code @ConfigProperty} and reaching past that to poke fields would test a
 * builder that is not the one deployments run. The overrides are written deliberately untidily —
 * surrounding spaces, an empty entry, a trailing comma — so the split's trimming is exercised by
 * the same test that exercises the synthesis.
 */
@QuarkusTest
@TestProfile(ZoneSnapshotSynthesisTest.Delegated.class)
public class ZoneSnapshotSynthesisTest extends DnsPersistenceTestSupport {

  /** Both keys set, as a delegated deployment must have them. */
  public static class Delegated implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of(
          "qits.dns.ns-names", " ns1.qits.eu , , ns2.qits.eu , ",
          "qits.dns.hostmaster", " hostmaster.qits.eu ");
    }
  }

  @Inject ZoneSnapshotBuilder builder;

  @Test
  public void theNsNameListIsSplitTrimmedAndStrippedOfBlanks() {
    assertEquals(List.of("ns1.qits.eu", "ns2.qits.eu"), builder.nsNames());
    assertTrue(builder.synthesisEnabled());
  }

  @Test
  public void everyZoneGetsAnSoaBuiltFromConfigurationAndItsOwnSerial() {
    DnsZone zone = zones.create("delegated.eu");
    records.create(zone.id, "@", DnsRecordType.A, "10.0.0.1", null);

    ZoneData data = builder.build().zoneFor("delegated.eu").orElseThrow();
    SoaData soa = data.soa().orElseThrow();
    assertEquals("ns1.qits.eu", soa.mname(), "mname is the first ns-name");
    assertEquals("hostmaster.qits.eu", soa.rname());
    // The serial is the zone row's, bumped by the record write above — it is what tells a caller
    // whether the answer it is getting includes the write it just made.
    assertEquals(2L, soa.serial());
    assertEquals(60, soa.minimum(), "minimum tracks qits.dns.ttl-seconds");
    assertEquals(ZoneSnapshotBuilder.SOA_REFRESH_SECONDS, soa.refresh());
    assertEquals(ZoneSnapshotBuilder.SOA_RETRY_SECONDS, soa.retry());
    assertEquals(ZoneSnapshotBuilder.SOA_EXPIRE_SECONDS, soa.expire());
    assertEquals(List.of("ns1.qits.eu", "ns2.qits.eu"), data.nsNames());
  }
}
