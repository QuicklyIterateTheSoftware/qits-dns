package eu.wohlben.qits.dns.api;

import static eu.wohlben.qits.dns.api.DnsApiFixtures.createRecord;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.createZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.recordBody;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.uniqueZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.zoneRecordsPath;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The three record rows of §6's table: create one, replace a {@code (name, type)} set, delete one.
 *
 * <p>The path asymmetry is asserted rather than assumed. A record is created under its zone —
 * {@code POST /zones/{id}/records}, because a record name is relative to an apex and means nothing
 * without one — and deleted at {@code DELETE /records/{id}}, because an id already names exactly
 * one row and carrying the zone alongside it would only create a pair that can disagree. Both
 * halves are exercised here, and the {@code Location} of the create is the second one: it points at
 * the address the row is deletable at, which is not below the address it was created under.
 */
@QuarkusTest
class DnsRecordApiTest {

  @Test
  void createsARecordAndPointsAtWhereItIsDeletable() {
    String zoneId = createZone(uniqueZone("record-create"));

    Response created =
        given()
            .contentType(ContentType.JSON)
            .body(recordBody("app.feature", "A", "192.0.2.42", 30))
            .when()
            .post(zoneRecordsPath(zoneId))
            .then()
            .statusCode(201)
            .body("name", equalTo("app.feature"))
            .body("type", equalTo("A"))
            .body("value", equalTo("192.0.2.42"))
            .body("ttl", equalTo(30))
            .body("zoneId", equalTo(zoneId))
            .extract()
            .response();

    String recordId = created.path("id");
    assertThat(created.header("Location"), endsWith("/dns/api/records/" + recordId));

    given().when().delete("/dns/api/records/" + recordId).then().statusCode(204);
    given().when().delete("/dns/api/records/" + recordId).then().statusCode(404);
  }

  @Test
  void acceptsEveryTypeTheSchemaHolds() {
    String zoneId = createZone(uniqueZone("record-types"));

    createRecord(zoneId, "@", "A", "192.0.2.1");
    createRecord(zoneId, "@", "AAAA", "2001:db8::1");
    // Stored without the trailing dot, which is the one normalisation this API performs: the wire
    // form has no dot and the resolver compares the target against zone fqdns that have none.
    given()
        .contentType(ContentType.JSON)
        .body(recordBody("*", "CNAME", "target.example.com.", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(201)
        .body("value", equalTo("target.example.com"));

    given()
        .when()
        .get("/dns/api/zones/" + zoneId)
        .then()
        .statusCode(200)
        .body("records", hasSize(3));
  }

  @Test
  void replacesAWholeNameAndTypeSet() {
    String zoneId = createZone(uniqueZone("record-replace"));
    createRecord(zoneId, "web", "A", "192.0.2.1");

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name",
                "web",
                "type",
                "A",
                "values",
                List.of("192.0.2.10", "192.0.2.11"),
                "ttl",
                5))
        .when()
        .put(zoneRecordsPath(zoneId))
        .then()
        // 200 and never 201, whether or not the name held anything before: the body describes a
        // state, and a status that flipped would make "did my deploy change something" a question
        // the status code answers wrongly.
        .statusCode(200)
        .body("records", hasSize(2))
        .body("records.value", contains("192.0.2.10", "192.0.2.11"))
        .body("records.ttl", contains(5, 5));

    given()
        .when()
        .get("/dns/api/zones/" + zoneId)
        .then()
        .body("records", hasSize(2))
        .body("records.value", contains("192.0.2.10", "192.0.2.11"));
  }

  @Test
  void replaceLeavesTheOtherTypesAtTheSameNameAlone() {
    String zoneId = createZone(uniqueZone("record-family"));
    createRecord(zoneId, "web", "A", "192.0.2.1");
    createRecord(zoneId, "web", "AAAA", "2001:db8::1");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "web", "type", "A", "values", List.of("192.0.2.2")))
        .when()
        .put(zoneRecordsPath(zoneId))
        .then()
        .statusCode(200)
        .body("records", hasSize(1));

    // Replace-by-SET, not replace-by-name: swapping a name's A rows must not quietly take its AAAA
    // rows with it, and a deployer that manages the two families separately is the normal case.
    given()
        .when()
        .get("/dns/api/zones/" + zoneId)
        .then()
        .body("records", hasSize(2))
        .body("records.find { it.type == 'AAAA' }.value", equalTo("2001:db8::1"));
  }

  @Test
  void unknownIdsAre404OnEveryRecordVerb() {
    String missingZone = UUID.randomUUID().toString();
    given()
        .contentType(ContentType.JSON)
        .body(recordBody("@", "A", "192.0.2.1", null))
        .when()
        .post(zoneRecordsPath(missingZone))
        .then()
        .statusCode(404);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "@", "type", "A", "values", List.of("192.0.2.1")))
        .when()
        .put(zoneRecordsPath(missingZone))
        .then()
        .statusCode(404);
    given().when().delete("/dns/api/records/" + UUID.randomUUID()).then().statusCode(404);
  }
}
