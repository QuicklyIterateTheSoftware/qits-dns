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
 * Half-configured the OTHER way round: {@code hostmaster} set, {@code ns-names} still blank. The
 * mirror of {@link ZoneSnapshotHalfConfiguredTest}, and it exists because that one alone does not
 * pin the rule it claims to.
 *
 * <p>All-or-nothing synthesis is currently one {@code &&} of two independent conditions, so both
 * directions fall out of the same expression and testing one looks like enough. It is not: the
 * moment that expression stops being symmetric — a short-circuit reordered, one key given a
 * fallback, a "well, an SOA with a made-up mname is better than no SOA" — only one direction
 * regresses, and a suite that tests only the other direction stays green while a zone starts
 * answering SOA with a nameserver hostname nobody registered. Which is precisely the failure
 * {@code ns-names} is blank to prevent.
 */
@QuarkusTest
@TestProfile(ZoneSnapshotHostmasterOnlyTest.HostmasterOnly.class)
public class ZoneSnapshotHostmasterOnlyTest extends DnsPersistenceTestSupport {

  /** The other one of the two keys set. */
  public static class HostmasterOnly implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.dns.hostmaster", "hostmaster.qits.eu");
    }
  }

  @Inject ZoneSnapshotBuilder builder;

  @Test
  public void anRnameWithNoNameserverIsStillNoSynthesis() {
    zones.create("half-other.eu");

    assertFalse(builder.synthesisEnabled());
    ZoneData data = builder.build().zoneFor("half-other.eu").orElseThrow();
    assertTrue(
        data.soa().isEmpty(),
        "an SOA needs an mname, and the only source of one is qits.dns.ns-names");
    assertTrue(data.nsNames().isEmpty());
  }
}
