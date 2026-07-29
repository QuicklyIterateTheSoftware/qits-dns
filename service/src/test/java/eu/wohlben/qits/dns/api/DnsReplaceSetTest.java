package eu.wohlben.qits.dns.api;

import static eu.wohlben.qits.dns.api.DnsApiFixtures.createRecord;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.createZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.uniqueZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.zoneRecordsPath;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code PUT /zones/{id}/records} is atomic in both directions, which is the property that makes it
 * the verb an automated deployer can actually run.
 *
 * <p>Three values become two and exactly two rows remain — no leftovers from the set it replaced,
 * which is the failure a delete-then-create loop written by a caller would produce halfway through.
 * And a replace that is REJECTED leaves the old set exactly as it was: the validation happens
 * before a single row is deleted, and the whole swap is one transaction, so there is no window in
 * which the name resolves to nothing or to half of an intended answer. That window is what a
 * caller doing this by hand cannot avoid, and closing it is the reason this verb exists rather than
 * being a convenience over POST and DELETE.
 */
@QuarkusTest
class DnsReplaceSetTest {

  @Test
  void threeValuesBecomeTwoAndNothingSurvivesTheSwap() {
    String zoneId = createZone(uniqueZone("swap"));
    replace(zoneId, "web", "A", List.of("192.0.2.1", "192.0.2.2", "192.0.2.3"))
        .then()
        .statusCode(200)
        .body("records", hasSize(3));

    replace(zoneId, "web", "A", List.of("192.0.2.7", "192.0.2.8"))
        .then()
        .statusCode(200)
        .body("records", hasSize(2))
        .body("records.value", contains("192.0.2.7", "192.0.2.8"));

    given()
        .when()
        .get("/dns/api/zones/" + zoneId)
        .then()
        .statusCode(200)
        .body("records", hasSize(2))
        .body("records.value", containsInAnyOrder("192.0.2.7", "192.0.2.8"));
  }

  @Test
  void aRejectedReplaceLeavesTheOriginalSetIntact() {
    String zoneId = createZone(uniqueZone("rollback"));
    replace(zoneId, "web", "A", List.of("192.0.2.1", "192.0.2.2", "192.0.2.3"))
        .then()
        .statusCode(200);

    // One bad literal in a list of three. The whole body is rejected — not "the two good ones
    // landed" — because a partially applied record set is an answer nobody asked for and the caller
    // has no way to tell it happened.
    replace(zoneId, "web", "A", List.of("192.0.2.7", "not-an-address", "192.0.2.9"))
        .then()
        .statusCode(400);

    given()
        .when()
        .get("/dns/api/zones/" + zoneId)
        .then()
        .body("records", hasSize(3))
        .body("records.value", containsInAnyOrder("192.0.2.1", "192.0.2.2", "192.0.2.3"));
  }

  @Test
  void aReplaceRejectedByAConflictLeavesTheOriginalSetIntact() {
    String zoneId = createZone(uniqueZone("rollback-conflict"));
    createRecord(zoneId, "alias", "A", "192.0.2.1");
    createRecord(zoneId, "alias", "A", "192.0.2.2");

    // A conflict rather than a malformed payload: the CNAME cannot join the A rows already there.
    // Same requirement, different code path through the rules — and the rows it could not join must
    // still be all of them.
    replace(zoneId, "alias", "CNAME", List.of("target.example.com")).then().statusCode(409);

    given()
        .when()
        .get("/dns/api/zones/" + zoneId)
        .then()
        .body("records", hasSize(2))
        .body("records.value", containsInAnyOrder("192.0.2.1", "192.0.2.2"));
  }

  private static Response replace(
      String zoneId, String name, String type, List<String> values) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", name, "type", type, "values", values))
        .when()
        .put(zoneRecordsPath(zoneId));
  }
}
