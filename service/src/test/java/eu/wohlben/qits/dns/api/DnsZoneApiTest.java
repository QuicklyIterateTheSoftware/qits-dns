package eu.wohlben.qits.dns.api;

import static eu.wohlben.qits.dns.api.DnsApiFixtures.createRecord;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.createZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.uniqueZone;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The four zone rows of §6's table over HTTP: create, list, read-with-records, delete.
 *
 * <p>The one assertion here worth a sentence is the {@code Location} header's shape. It is built
 * from {@code UriInfo} rather than from a literal, so what it proves is that {@code
 * quarkus.rest.path} really is the prefix the routes ended up under — a resource that had
 * accidentally repeated the {@code dns} segment in its {@code @Path} would answer at {@code
 * /dns/api/dns/zones} and say so here, in the header, rather than only in whatever the caller then
 * failed to fetch.
 */
@QuarkusTest
class DnsZoneApiTest {

  @Test
  void createsAZoneAndPointsAtIt() {
    String fqdn = uniqueZone("create");

    Response created =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("fqdn", fqdn))
            .when()
            .post("/dns/api/zones")
            .then()
            .statusCode(201)
            .body("fqdn", equalTo(fqdn))
            // Serial 1, not 0: "never written" and "written once" have to be distinguishable, and
            // this is the field an automated deployer polls to see its own change land.
            .body("serial", equalTo(1))
            .extract()
            .response();

    String id = created.path("id");
    assertThat(created.header("Location"), endsWith("/dns/api/zones/" + id));

    given().when().get("/dns/api/zones/" + id).then().statusCode(200).body("fqdn", equalTo(fqdn));
  }

  @Test
  void listsZones() {
    String fqdn = uniqueZone("list");
    createZone(fqdn);

    // `hasItem` rather than a count: every @QuarkusTest on this profile shares one database, so the
    // listing legitimately holds whatever else the suite created. A test that asserted a size would
    // be asserting the order the suite happens to run in.
    given()
        .when()
        .get("/dns/api/zones")
        .then()
        .statusCode(200)
        .body("zones.fqdn", hasItem(fqdn));
  }

  @Test
  void readsOneZoneWithItsRecordsEmbedded() {
    String fqdn = uniqueZone("detail");
    String zoneId = createZone(fqdn);
    createRecord(zoneId, "@", "A", "192.0.2.1");
    createRecord(zoneId, "www", "AAAA", "2001:db8::1");

    given()
        .when()
        .get("/dns/api/zones/" + zoneId)
        .then()
        .statusCode(200)
        .body("fqdn", equalTo(fqdn))
        // Three writes into this zone: two records, and the serial started at 1.
        .body("serial", equalTo(3))
        .body("records", hasSize(2))
        .body("records.name", hasItem("www"))
        .body("records.value", hasItem("2001:db8::1"))
        // Null and not 60: the record carries no override, and filling the server default in here
        // would make the next round-trip PIN it — a record that tracked the default silently
        // becoming one that does not.
        .body("records[0].ttl", nullValue());
  }

  @Test
  void deletesAZoneAndTheRecordsUnderIt() {
    String zoneId = createZone(uniqueZone("delete"));
    String recordId = createRecord(zoneId, "@", "A", "192.0.2.9");

    given().when().delete("/dns/api/zones/" + zoneId).then().statusCode(204);

    given().when().get("/dns/api/zones/" + zoneId).then().statusCode(404);
    // The record went with it. The FK lives in SQL and not in the object model, so nothing cascades
    // on its own — a zone delete that left rows behind would fail in the database, not here.
    given().when().delete("/dns/api/records/" + recordId).then().statusCode(404);
  }

  @Test
  void unknownZoneIdsAre404() {
    String missing = UUID.randomUUID().toString();
    given().when().get("/dns/api/zones/" + missing).then().statusCode(404);
    given().when().delete("/dns/api/zones/" + missing).then().statusCode(404);
  }
}
