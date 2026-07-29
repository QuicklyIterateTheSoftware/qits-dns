package eu.wohlben.qits.dns.api;

import static eu.wohlben.qits.dns.api.DnsApiFixtures.uniqueZone;
import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The shipped default: {@code qits.dns.token} blank, so {@link DnsTokenFilter} is a no-op and an
 * unauthenticated write goes through. The guarded half is {@link DnsTokenGuardTest}.
 *
 * <p>This is the mode every other test in the suite runs under, so in one sense it is asserted
 * hundreds of times a build. It is stated once explicitly anyway, because "blank is open" is a
 * DECISION — it is what keeps {@code quarkus:dev} and the suite free of ceremony, and it is also
 * what a deployment that forgot {@code QITS_DNS_TOKEN} gets, which the README calls out as one of
 * the four settings a real deployment must set. A rule that only exists as an emergent property of
 * other tests is a rule nobody can find when they need to change it.
 *
 * <p>Note the ABSOLUTE path, as in the sibling: this must fail if the route moved, not follow it.
 */
@QuarkusTest
class DnsBlankTokenGuardTest {

  @Test
  void aWriteWithNoTokenHeaderIsAllowedWhenNoTokenIsConfigured() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", uniqueZone("open")))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(201);
  }
}
