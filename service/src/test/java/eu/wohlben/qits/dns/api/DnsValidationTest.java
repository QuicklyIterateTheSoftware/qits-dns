package eu.wohlben.qits.dns.api;

import static eu.wohlben.qits.dns.api.DnsApiFixtures.createRecord;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.createZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.recordBody;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.uniqueZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.zoneRecordsPath;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every §3 rejection, seen from HTTP.
 *
 * <p>{@code DnsNamesTest} already asserts these rules as pure functions, and this file is
 * deliberately not a second copy of it. What it adds is the wiring: that the API layer actually
 * CALLS the validator on every payload it accepts, and that the exception the validator throws
 * arrives as the status code {@code error/} says it carries. A rule enforced perfectly in {@code
 * DnsNames} and never reached from a resource is a rule this service does not have, and nothing in
 * the unit suite can tell the difference.
 *
 * <p>Which rejections are 400 and which are 409 is the load-bearing half. 400 is a payload that is
 * wrong on its own terms — a name of no legal shape, a value that is not an address. 409 is a
 * payload that is perfectly well-formed and that this database cannot accept: a CNAME at the apex,
 * a CNAME beside a sibling, a duplicate, a zone overlapping one already configured. A caller
 * retrying a 409 with the same body will keep failing and needs to change the world rather than the
 * request, which is exactly what the two codes are for.
 */
@QuarkusTest
class DnsValidationTest {

  // --- zones ------------------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "eu", // one label: a zone is a registered domain, never a bare TLD
        "Qits-Dev.eu", // uppercase is rejected, never silently lowercased
        "qits-dev.eu.", // the trailing dot: zones are stored without one
        "-lead.eu",
        "trail-.eu",
        "a..eu",
        "" // blank
      })
  void rejectsMalformedZoneFqdns(String fqdn) {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", fqdn))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(400);
  }

  @Test
  void rejectsAZoneBodyThatSaysNothing() {
    given()
        .contentType(ContentType.JSON)
        .body("{}")
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(400)
        .body("message", containsString("required"));
  }

  @Test
  void rejectsAZoneThatAlreadyExists() {
    String fqdn = uniqueZone("dup");
    createZone(fqdn);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", fqdn))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(409)
        .body("message", containsString("already exists"));
  }

  @Test
  void rejectsAZoneInsideAConfiguredZone() {
    String outer = uniqueZone("suffix");
    createZone(outer);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", "child." + outer))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(409)
        .body("message", containsString("lies inside"));
  }

  @Test
  void rejectsAZoneThatWouldContainAConfiguredZone() {
    // The other direction, and it matters as much: whichever of the two arrives second, two zones
    // where one contains the other means the loser's records simply stop being served — data loss
    // dressed as a delegation.
    String outer = uniqueZone("prefix");
    createZone("child." + outer);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", outer))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(409)
        .body("message", containsString("would contain"));
  }

  @Test
  void acceptsAZoneThatMerelyEndsWithTheTextOfAnother() {
    // The overlap comparison is at a LABEL BOUNDARY. `notoverlap.test` ending with `overlap.test`
    // as a string is not containment, and rejecting it would be a rule about substrings rather than
    // about DNS.
    String base = uniqueZone("boundary");
    createZone(base);

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", "not" + base))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(201);
  }

  // --- record names -----------------------------------------------------------------------------

  @ParameterizedTest
  @ValueSource(
      strings = {
        "a.b.c", // three labels: the grammar stops at two, and so does the matching table
        "web.*", // a wildcard only ever occupies the LEFTMOST label
        "WWW", // uppercase
        "www.", // trailing dot
        "-lead",
        "trail-",
        "a..b"
      })
  void rejectsRecordNamesOfNoLegalShape(String name) {
    String zoneId = createZone(uniqueZone("shape"));

    given()
        .contentType(ContentType.JSON)
        .body(recordBody(name, "A", "192.0.2.1", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(400)
        .body("message", containsString("six legal shapes"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"@", "web", "app.feature", "*", "*.feature", "*.*"})
  void acceptsEachOfTheSixShapes(String name) {
    String zoneId = createZone(uniqueZone("shapes-ok"));

    given()
        .contentType(ContentType.JSON)
        .body(recordBody(name, "A", "192.0.2.1", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(201)
        .body("name", equalTo(name));
  }

  // --- values -----------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({
    "A,192.0.2.256",
    "A,10.1", // inet_aton would take this; a dotted quad has four octets
    "A,010.1.1.1", // a leading zero is OCTAL to the C library — two readings, one string
    "A,0x0a000001",
    "A,not-an-address",
    "AAAA,gggg::1",
    "AAAA,fe80::1%eth0", // a scope id is meaningful only on the host that wrote it
    "AAAA,2001:db8:::1",
    "AAAA,192.0.2.1", // right shape, wrong family
    "CNAME,single", // a CNAME target is an absolute hostname of at least two labels
    "CNAME,Target.Example.Com",
    "CNAME,-lead.example.com"
    // NOT "CNAME,192.0.2.1": a dotted quad is a perfectly legal sequence of LDH labels, so it is
    // accepted as a target and will simply never resolve. Rejecting it would need a rule that
    // digits-only labels are not hostnames, which is not true (`1.example.com` is a name), and the
    // mistake it would prevent is not one this validator can tell from a legitimate name.
  })
  void rejectsValuesThatDoNotMatchTheirType(String type, String value) {
    String zoneId = createZone(uniqueZone("value"));

    given()
        .contentType(ContentType.JSON)
        .body(recordBody("web", type, value, null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(400);
  }

  @Test
  void rejectsANegativeTtl() {
    String zoneId = createZone(uniqueZone("ttl"));

    given()
        .contentType(ContentType.JSON)
        .body(recordBody("web", "A", "192.0.2.1", -1))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(400);
  }

  // --- the state-dependent rules ------------------------------------------------------------

  @Test
  void rejectsACnameAtTheApexAndSaysWhatToDoInstead() {
    String zoneId = createZone(uniqueZone("apex-cname"));

    given()
        .contentType(ContentType.JSON)
        .body(recordBody("@", "CNAME", "target.example.com", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(409)
        // The message is half the point. Whoever hit this wanted something reasonable — "the apex
        // should follow the same target as the wildcards" — and the workaround is one they can
        // apply immediately, so the response has to name it rather than merely cite the RFC.
        .body("message", containsString("RFC 1034"))
        .body("message", containsString("A/AAAA"));
  }

  @Test
  void rejectsACnameAtTheApexOnTheReplaceVerbToo() {
    String zoneId = createZone(uniqueZone("apex-cname-put"));

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of("name", "@", "type", "CNAME", "values", List.of("target.example.com")))
        .when()
        .put(zoneRecordsPath(zoneId))
        .then()
        .statusCode(409)
        .body("message", containsString("A/AAAA"));
  }

  @Test
  void rejectsACnameBesideAnExistingRecord() {
    String zoneId = createZone(uniqueZone("cname-beside"));
    createRecord(zoneId, "web", "A", "192.0.2.1");

    given()
        .contentType(ContentType.JSON)
        .body(recordBody("web", "CNAME", "target.example.com", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(409)
        .body("message", containsString("cannot hold a CNAME beside"));
  }

  @Test
  void rejectsARecordBesideAnExistingCname() {
    String zoneId = createZone(uniqueZone("beside-cname"));
    createRecord(zoneId, "alias", "CNAME", "target.example.com");

    // The rule is symmetric, and both directions are reachable from this API — a caller who adds
    // the CNAME first is not a caller who has earned a sibling.
    given()
        .contentType(ContentType.JSON)
        .body(recordBody("alias", "A", "192.0.2.1", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(409)
        .body("message", containsString("cannot hold a CNAME beside"));
  }

  @Test
  void rejectsASecondCnameAtTheSameName() {
    String zoneId = createZone(uniqueZone("two-cnames"));
    createRecord(zoneId, "alias", "CNAME", "one.example.com");

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of(
                "name",
                "alias",
                "type",
                "CNAME",
                "values",
                List.of("one.example.com", "two.example.com")))
        .when()
        .put(zoneRecordsPath(zoneId))
        .then()
        .statusCode(409)
        .body("message", containsString("exactly one CNAME"));
  }

  @Test
  void rejectsADuplicateRow() {
    String zoneId = createZone(uniqueZone("duplicate"));
    createRecord(zoneId, "web", "A", "192.0.2.1");

    given()
        .contentType(ContentType.JSON)
        .body(recordBody("web", "A", "192.0.2.1", null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(409)
        .body("message", containsString("already exists"));
  }

  @Test
  void rejectsAReplaceWithNoValues() {
    String zoneId = createZone(uniqueZone("empty-replace"));
    createRecord(zoneId, "web", "A", "192.0.2.1");

    // Not "delete the set": a body describing nothing is a serialisation accident far more often
    // than an intent, and deleting rows on the strength of an accident is not a mistake this API
    // should be able to make.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "web", "type", "A", "values", Collections.emptyList()))
        .when()
        .put(zoneRecordsPath(zoneId))
        .then()
        .statusCode(400)
        .body("message", containsString("at least one value"));
  }

  @Test
  void rejectsAReplaceThatRepeatsAValue() {
    String zoneId = createZone(uniqueZone("repeat-replace"));

    given()
        .contentType(ContentType.JSON)
        .body(
            Map.of("name", "web", "type", "A", "values", List.of("192.0.2.1", "192.0.2.1")))
        .when()
        .put(zoneRecordsPath(zoneId))
        .then()
        .statusCode(400)
        .body("message", containsString("distinct"));
  }
}
