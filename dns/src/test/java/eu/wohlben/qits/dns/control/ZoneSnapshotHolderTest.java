package eu.wohlben.qits.dns.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.entity.DnsZone;
import eu.wohlben.qits.dns.resolve.DnsResolver;
import eu.wohlben.qits.dns.resolve.ResponseCode;
import eu.wohlben.qits.dns.resolve.ZoneSnapshot;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The publication seam: a snapshot exists from boot, a rebuild replaces it wholesale, and the
 * resolver bean sees whatever was published last.
 *
 * <p>This is the only test that runs the resolver through CDI rather than constructing it — not to
 * re-test §3, which the pure suite owns, but to prove the wiring the wire layer will inject is the
 * one the holder feeds.
 */
@QuarkusTest
public class ZoneSnapshotHolderTest extends DnsPersistenceTestSupport {

  @Inject ZoneSnapshotHolder holder;
  @Inject DnsResolver resolver;

  @Test
  public void aSnapshotExistsFromBootAndIsNeverNull() {
    assertNotNull(holder.current());
  }

  @Test
  public void aRebuildPublishesAWholeNewSnapshot() {
    ZoneSnapshot before = holder.current();
    zones.create("published.eu");

    holder.rebuild();

    assertTrue(holder.current().zoneFor("published.eu").isPresent());
    assertEquals(0, before.zones().size(), "the previous snapshot is not mutated, it is replaced");
  }

  @Test
  public void theResolverAnswersFromWhateverWasPublishedLast() {
    DnsZone zone = zones.create("published.eu");
    records.create(zone.id, "feature", DnsRecordType.A, "10.0.0.1", null);

    // Nothing published yet: the write is committed but the hot path has not been told.
    holder.publish(ZoneSnapshot.empty());
    assertEquals(
        ResponseCode.REFUSED, resolver.resolve("feature.published.eu", 1).rcode(), "before");

    holder.rebuild();
    assertEquals(
        "10.0.0.1", resolver.resolve("feature.published.eu", 1).answers().get(0).value(), "after");
  }

  @Test
  public void currentIsTheSameReferenceUntilSomethingRebuilds() {
    ZoneSnapshot first = holder.current();
    assertSame(first, holder.current());
  }
}
