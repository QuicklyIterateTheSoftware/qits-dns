package eu.wohlben.qits.dns.api;

import static eu.wohlben.qits.dns.api.DnsApiFixtures.uniqueZone;
import static io.restassured.RestAssured.given;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * With {@code qits.dns.token} configured, {@link DnsTokenFilter} guards every write under {@code
 * /dns/api} and no read. The other half of the guard — blank means open — is {@link
 * DnsBlankTokenGuardTest}, which is a separate class only because a config value is a property of
 * the application a {@code @QuarkusTest} class boots, so the two modes cannot be two methods.
 *
 * <p><b>Every request below addresses the ABSOLUTE path.</b> That is the whole reason qits-ci's
 * equivalent test is written this way and it is worth repeating: a filter matching a path that no
 * longer exists, or a resource that moved out from under it, produces a request that reaches the
 * handler unguarded — and a test written against a relative path or a builder-derived one would
 * follow the move and stay green. Spelled out, the same regression shows up as a 201 where a 401 is
 * asserted, which is exactly the shape of the bug.
 */
@QuarkusTest
@TestProfile(DnsTokenGuardTest.WithToken.class)
class DnsTokenGuardTest {

  static final String TOKEN = "dns-guard-test-token";

  /** The deployment case: a token is set, so the write verbs are closed. */
  public static class WithToken implements QuarkusTestProfile {
    @Override
    public Map<String, String> getConfigOverrides() {
      return Map.of("qits.dns.token", TOKEN);
    }
  }

  @Test
  void aWriteWithoutTheTokenIs401() {
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("fqdn", uniqueZone("guarded")))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(401);
  }

  @Test
  void aWriteWithTheWrongTokenIs401() {
    given()
        .contentType(ContentType.JSON)
        .header(DnsTokenFilter.TOKEN_HEADER, "not-the-token")
        .body(Map.of("fqdn", uniqueZone("guarded")))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(401);
  }

  @Test
  void everyWriteVerbIsGuardedAndNotJustTheOneResource() {
    // The divergence from the ported pattern, asserted: qits-ci guards one named resource, this
    // guards the whole surface, because a write here changes what a public nameserver answers and a
    // route added later must not have to remember to opt in.
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "web", "type", "A", "values", List.of("192.0.2.1")))
        .when()
        .put("/dns/api/zones/any-id/records")
        .then()
        .statusCode(401);
    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "web", "type", "A", "value", "192.0.2.1"))
        .when()
        .post("/dns/api/zones/any-id/records")
        .then()
        .statusCode(401);
    given().when().delete("/dns/api/records/any-id").then().statusCode(401);
    given().when().delete("/dns/api/zones/any-id").then().statusCode(401);
  }

  @Test
  void readsAreNotGuarded() {
    // 200, not 401: what this API holds is what the UDP socket already tells the open internet, so
    // a token on a GET would protect nothing that is not public by construction.
    given().when().get("/dns/api/zones").then().statusCode(200);
  }

  @Test
  void aWriteCarryingTheTokenGoesThrough() {
    given()
        .contentType(ContentType.JSON)
        .header(DnsTokenFilter.TOKEN_HEADER, TOKEN)
        .body(Map.of("fqdn", uniqueZone("token-ok")))
        .when()
        .post("/dns/api/zones")
        .then()
        .statusCode(201);
  }
}
