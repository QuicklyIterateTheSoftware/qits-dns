package eu.wohlben.qits.dns.api;

import static io.restassured.RestAssured.given;

import io.restassured.http.ContentType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The two lines every API test starts with — make a zone, put a record in it — and the reason zone
 * names in this suite look like {@code apex-3f2a91c4.test}.
 *
 * <p><b>Every zone fqdn is unique per test.</b> {@code @QuarkusTest} classes on the same profile
 * share one application and therefore one in-memory database, and this module's write rules are
 * about the whole database rather than about one row: a zone may not be a suffix or a prefix of any
 * OTHER zone, so a fixed name reused across two classes is a 409 that arrives only when the two run
 * in the same JVM and in a particular order. The random label makes the tests independent of what
 * else ran, which is the property a shared-instance suite has to buy explicitly.
 *
 * <p>The suffix is {@code .test} — reserved by RFC 6761 for exactly this, so nothing here can ever
 * collide with a name somebody registered, and a stray query escaping a test cannot reach a real
 * server.
 *
 * <p>These helpers assert their own status codes and return ids. They are for ARRANGING a test, not
 * for exercising the surface — a test about {@code POST /zones} calls it directly and looks at the
 * whole response.
 */
final class DnsApiFixtures {

  private DnsApiFixtures() {}

  /** Where a zone's records are created and replaced. Spelled once; §6 spells it twice. */
  static String zoneRecordsPath(String zoneId) {
    return "/dns/api/zones/" + zoneId + "/records";
  }

  /** A zone fqdn nothing else in the suite can be holding. */
  static String uniqueZone(String hint) {
    return hint + "-" + UUID.randomUUID().toString().substring(0, 8) + ".test";
  }

  /** Creates a zone and returns its id. */
  static String createZone(String fqdn) {
    return given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", fqdn))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  /** Creates one record and returns its id. */
  static String createRecord(String zoneId, String name, String type, String value) {
    return given()
        .contentType(ContentType.JSON)
        .body(recordBody(name, type, value, null))
        .when()
        .post(zoneRecordsPath(zoneId))
        .then()
        .statusCode(201)
        .extract()
        .path("id");
  }

  /**
   * A record payload. {@link LinkedHashMap} rather than {@code Map.of} because a null {@code ttl}
   * is the normal case and {@code Map.of} rejects null values outright — and "omit the field" and
   * "send it null" must both mean "follow the server default", which is what a test asserting the
   * default has to be able to say.
   */
  static Map<String, Object> recordBody(String name, String type, String value, Integer ttl) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("name", name);
    body.put("type", type);
    body.put("value", value);
    body.put("ttl", ttl);
    return body;
  }
}
