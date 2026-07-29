package eu.wohlben.qits.dns.control;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.resolve.ZoneData;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Half-configured: {@code ns-names} set, {@code hostmaster} still blank.
 *
 * <p>Synthesis is all-or-nothing, and this is the test that says so. A zone answering NS out of a
 * configured name list while having no SOA is a half-delegation that LOOKS configured to whoever
 * queries it — the delegation appears live right up to the first negative answer nothing can cache
 * — and the two keys are set by the same act of pointing a registrar at this server. So neither
 * takes effect without the other, and the boot warning names both.
 */
@QuarkusTest
@TestProfile(ZoneSnapshotHalfConfiguredTest.NsOnly.class)
public class ZoneSnapshotHalfConfiguredTest extends DnsPersistenceTestSupport {

  /** One of the two keys set — the state this class exists to pin. */
  public static class NsOnly implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.dns.ns-names", "ns1.qits.eu");
    }
  }

  @Inject ZoneSnapshotBuilder builder;

  @Test
  public void neitherKeyTakesEffectWithoutTheOther() {
    zones.create("half.eu");

    assertFalse(builder.synthesisEnabled());
    ZoneData data = builder.build().zoneFor("half.eu").orElseThrow();
    assertTrue(data.soa().isEmpty());
    assertTrue(data.nsNames().isEmpty(), "NS is not answered out of a zone with no SOA");
  }
}
