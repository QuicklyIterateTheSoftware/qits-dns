package eu.wohlben.qits.dns.api;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.resolve.WireType;
import eu.wohlben.qits.dns.wire.WireClient;
import io.quarkus.test.junit.QuarkusIntegrationTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Section;

/**
 * The whole service as it is <b>packaged</b> — the fast-jar under {@code mvn verify
 * -DskipITs=false}, the GraalVM binary under {@code mvn verify -Dnative}. Every other test in this
 * repo runs in a JVM holding the test classpath; this one starts the artifact a deployment actually
 * receives, which is the only way to catch what a closed-world native build silently loses: a
 * classpath resource resolved by scanning, a service loader dropped, a class whose static
 * initializer the image refused.
 *
 * <p><b>It is not a second boundary test and behaviour does not belong in it.</b> The API surface
 * is {@link DnsZoneApiTest} and friends, the resolution contract is the {@code dns} module's, and
 * the loop is {@link DnsWriteThenResolveTest} — all of which run in a fraction of the time. What
 * is asserted below is only what a {@code @QuarkusTest} structurally cannot see, because it exists
 * only once the app has been built:
 *
 * <ul>
 *   <li>the HTTP routes are where the <b>build-time</b> config says. {@code quarkus.rest.path} and
 *       {@code quarkus.http.non-application-root-path} are baked in at augmentation, so a segment
 *       regression is invisible to a suite that boots from the same properties it is asserting;
 *   <li>the <b>shipped</b> {@code ${user.home}}-rooted file H2 connects and Flyway found {@code
 *       db/dns/migration} as a real classpath resource. The datasource URL is where qits-ci's
 *       binary died at boot (an {@code AUTO_SERVER=TRUE} wanting H2's TCP server, a class the
 *       image does not contain) while every JVM test there stayed green;
 *   <li>one API write lands and one UDP query is answered <b>by the packaged process</b>. That is
 *       what keeps the dnsjava-in-native finding true rather than merely once-true: the codec's
 *       behaviour under a closed-world build is not something the JVM suite can have an opinion
 *       about, and the two config lines that make it work are ablation-proven and easy to delete.
 * </ul>
 */
@QuarkusIntegrationTest
@TestProfile(DnsPackagedSurfaceIT.PackagedUnderTarget.class)
public class DnsPackagedSurfaceIT {

  /**
   * Relocates the launched artifact's state under {@code target/} by moving {@code user.home}, not
   * by restating the settings — the qits-ci arrangement, and for the same reason. This module's
   * datasource URL is {@code ${user.home}}-rooted in the {@code dns} jar's {@code
   * META-INF/microprofile-config.properties}, so overriding the home leaves the SHIPPED url itself
   * under test. Spelling a JDBC url out here instead would make this IT pass against a default no
   * deployment can boot, which is precisely the failure it exists to catch.
   *
   * <p>The overrides ride to the launched process as {@code -D} arguments, so every one of them has
   * to be runtime config. Both are.
   */
  public static class PackagedUnderTarget implements QuarkusTestProfile {

    static final Path HOME = Path.of("target", "dns-packaged-it-home").toAbsolutePath();

    /**
     * The DNS port the launched artifact binds.
     *
     * <p>It cannot be 0 here, and that is the one thing this IT does differently from every other
     * test in the repo. {@code qits.dns.port=0} is what the suite uses everywhere else, and it
     * works because {@code DnsWireServer.boundPort()} is reachable in-process; across a process
     * boundary there is no such call, and the artifact's log line is not something to parse.
     * So the port is chosen HERE and handed to the process.
     *
     * <p><b>The TOCTOU is real and accepted.</b> Between closing the probe socket and the artifact
     * binding it, another process on this machine can take the port; then the launch fails loudly
     * at bind — which is the failure mode worth having, because the alternative (a fixed 8053)
     * fights a developer's own server and every parallel build, deterministically rather than
     * rarely.
     */
    static final int DNS_PORT = pickFreeUdpPort();

    @Override
    public Map<String, String> getConfigOverrides() {
      deleteRecursively(HOME);
      return Map.of(
          "user.home", HOME.toString(),
          "qits.dns.port", Integer.toString(DNS_PORT));
    }

    private static int pickFreeUdpPort() {
      try (DatagramSocket probe = new DatagramSocket(0)) {
        return probe.getLocalPort();
      } catch (IOException e) {
        throw new IllegalStateException("could not find a free UDP port for the packaged DNS", e);
      }
    }
  }

  @Test
  public void theRoutesAreWhereTheBuildTimeConfigSaysTheyAre() {
    // quarkus.rest.path — the management API. qits-gateway routes verbatim by prefix, so the
    // service itself has to serve /dns/..., and there is no unprefixed form to fall back to.
    given().when().get("/dns/api/zones").then().statusCode(200);

    // quarkus.http.non-application-root-path sits OUTSIDE quarkus.rest.path and has to carry the
    // segment on its own, or the document is at / and unreachable through the gateway.
    given().when().get("/dns/q/openapi").then().statusCode(200);
    given().when().get("/dns/q/swagger-ui/").then().statusCode(200);
  }

  @Test
  @Timeout(60)
  public void aWriteLandsInTheShippedFileH2AndTheBinaryAnswersItOverUdp() throws Exception {
    String fqdn = "packaged-" + UUID.randomUUID().toString().substring(0, 8) + ".test";

    String zoneId =
        given()
            .contentType(ContentType.JSON)
            .body(Map.of("fqdn", fqdn))
            .when()
            .post("/dns/api/zones")
            .then()
            .statusCode(201)
            .extract()
            .path("id");

    given()
        .contentType(ContentType.JSON)
        .body(Map.of("name", "@", "type", "A", "value", "192.0.2.77"))
        .when()
        .post("/dns/api/zones/" + zoneId + "/records")
        .then()
        .statusCode(201);

    // The whole loop, from outside the process: an HTTP write, then a real datagram on the port the
    // artifact was told to bind, answered by dnsjava running inside a closed-world image.
    Message response =
        WireClient.parse(
            WireClient.udp(
                PackagedUnderTarget.DNS_PORT,
                WireClient.query(0x7777, fqdn + ".", WireType.A.code())));
    assertEquals(Rcode.NOERROR, response.getHeader().getRcode());
    assertEquals(1, response.getSection(Section.ANSWER).size());
    assertEquals(
        "192.0.2.77",
        ((ARecord) response.getSection(Section.ANSWER).getFirst()).getAddress().getHostAddress());

    // The write above would look identical against an in-memory database, so pin that the process
    // really opened the ${user.home}-rooted file H2 the dns jar ships and that Flyway applied
    // db/dns/migration out of a real classpath resource — a migration is loaded by SCANNING a
    // location, which is exactly the shape a native build drops.
    assertTrue(
        Files.isDirectory(PackagedUnderTarget.HOME.resolve(".qits/data/dns/h2")),
        "the shipped file-H2 default must be what the packaged process opened");
  }

  private static void deleteRecursively(Path root) {
    if (!Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
    } catch (Exception e) {
      throw new IllegalStateException("could not clear " + root, e);
    }
  }
}
