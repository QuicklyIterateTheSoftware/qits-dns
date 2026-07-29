package eu.wohlben.qits.dns.api;

import static eu.wohlben.qits.dns.api.DnsApiFixtures.createRecord;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.createZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.recordBody;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.uniqueZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.zoneRecordsPath;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.control.ZoneSnapshotHolder;
import eu.wohlben.qits.dns.resolve.StoredRecord;
import eu.wohlben.qits.dns.resolve.ZoneData;
import eu.wohlben.qits.dns.resolve.ZoneSnapshot;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The ordering the whole design rests on: <b>a rebuild happens after the mutating transaction
 * commits, and a write that fails never publishes anything.</b>
 *
 * <p>The control services are {@code @Transactional} and deliberately do not rebuild — they cannot
 * see their own commit — so the call lives in the resource, on the line after the service method
 * returns. That is the simplest arrangement that is honest about the ordering, and it is the one
 * the design describes. The alternative, a {@code TransactionSynchronizationRegistry}
 * afterCompletion hook, is what you reach for when the commit belongs to somebody else; here the
 * boundary owns the transaction outright, and a hook would hide the ordering in a callback.
 *
 * <p>Both halves are asserted, and the failure half is the sharp one. It does not merely check that
 * the snapshot's CONTENTS are unchanged — a rebuild after a rolled-back write would produce
 * identical contents and the test would pass while the ordering guarantee had quietly become an
 * accident. It asserts the snapshot REFERENCE is the same object, which is only true if {@code
 * rebuild()} was never called at all. That is the difference between "the rejected write did not
 * publish" and "the rejected write happened to publish the same thing".
 */
@QuarkusTest
class DnsSnapshotRebuildTest {

  @Inject ZoneSnapshotHolder snapshots;

  @Test
  void aSuccessfulWriteIsVisibleInTheSnapshotBeforeTheResponseReturns() {
    String fqdn = uniqueZone("rebuild");
    String zoneId = createZone(fqdn);

    // The zone exists in the snapshot the moment its 201 came back — the rebuild ran, and it ran
    // late enough to see a committed row.
    assertTrue(snapshots.current().zoneFor(fqdn).isPresent(), "the zone must be published");

    createRecord(zoneId, "web", "A", "192.0.2.5");
    assertEquals(List.of("192.0.2.5"), valuesAt(snapshots.current(), fqdn, "web"));

    // …and so is a delete. The snapshot is rebuilt WHOLE from committed state, so a removal cannot
    // be the thing an incremental invalidation forgets.
    String recordId = createRecord(zoneId, "other", "A", "192.0.2.6");
    given().when().delete("/dns/api/records/" + recordId).then().statusCode(204);
    assertTrue(valuesAt(snapshots.current(), fqdn, "other").isEmpty());
  }

  @Test
  void aRejectedWriteDoesNotEvenRebuild() {
    String fqdn = uniqueZone("no-publish");
    String zoneId = createZone(fqdn);
    createRecord(zoneId, "web", "A", "192.0.2.5");

    ZoneSnapshot before = snapshots.current();

    given()
        .contentType(ContentType.JSON)
        .body(recordBody("web", "A", "not-an-address", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(400);
    given()
        .contentType(ContentType.JSON)
        .body(recordBody("@", "CNAME", "target.example.com", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(409);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "web", "type", "A", "values", List.of()))
        .when()
        .put(zoneRecordsPath(zoneId))
        .then()
        .statusCode(400);
    given().when().delete("/dns/api/records/does-not-exist").then().statusCode(404);

    assertSame(
        before,
        snapshots.current(),
        "a write that threw must not reach the rebuild — the resolver is still answering from the "
            + "snapshot it had before the request arrived");
  }

  @Test
  void aFailedZoneWriteDoesNotEvenRebuild() {
    String fqdn = uniqueZone("no-publish-zone");
    createZone(fqdn);

    ZoneSnapshot before = snapshots.current();

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", fqdn))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(409);
    given().when().delete("/dns/api/zones/does-not-exist").then().statusCode(404);

    assertSame(before, snapshots.current());
  }

  private static List<String> valuesAt(ZoneSnapshot snapshot, String fqdn, String name) {
    ZoneData zone = snapshot.zoneFor(fqdn).orElseThrow();
    List<StoredRecord> rows = zone.byName().get(name);
    return rows == null ? List.of() : rows.stream().map(StoredRecord::value).toList();
  }
}
