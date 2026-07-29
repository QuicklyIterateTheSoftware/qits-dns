package eu.wohlben.qits.dns.api;

import static eu.wohlben.qits.dns.api.DnsApiFixtures.createRecord;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.createZone;
import static eu.wohlben.qits.dns.api.DnsApiFixtures.uniqueZone;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.resolve.WireType;
import eu.wohlben.qits.dns.wire.DnsWireServer;
import eu.wohlben.qits.dns.wire.WireClient;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.CNAMERecord;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

/**
 * <b>The loop, and the reason this service is one process rather than two.</b> A record is written
 * over HTTP and then asked for over real UDP, on the port the listener actually bound — no docker,
 * no fixture server, no restart between the two. Everything else in this repo tests one half: the
 * §3 suite proves the rules against a hand-built snapshot, the wire suite proves the bytes against
 * a scripted resolver, and the API suite above proves the writes against the database. This is the
 * only test in which a write and an answer are the same event.
 *
 * <p><b>It runs against the REAL {@code DnsResolverImpl}, and that is not automatic.</b> {@code
 * ScriptedDnsResolver} is a CDI {@code @Alternative} in the wire suite; while it carried a global
 * {@code @Priority} it replaced the resolver for the entire module, which would have made this file
 * a test of canned answers dressed as a test of the loop — green, and worth nothing. It is now
 * selected only by {@code ScriptedResolverProfile}, which the wire tests declare and this one does
 * not. If a future change makes that fake global again, the assertions below stop being about
 * anything and no failure will say so; the safeguard is that they are written against records this
 * file created and nothing scripted.
 *
 * <p>Three shapes are covered because they are the three the design promises and the three a
 * wildcard implementation gets wrong in different ways: an apex A, a {@code *} CNAME with the
 * in-zone chase appended, and an explicit name beating the wildcard that would otherwise cover it.
 * Then a DELETE over HTTP changes what the next UDP query gets back — which is the whole claim of
 * "takes effect on the next query with no restart", made as an observation rather than as a
 * paragraph.
 */
@QuarkusTest
class DnsWriteThenResolveTest {

  @Inject DnsWireServer server;

  @Test
  @Timeout(30)
  void aRecordWrittenOverHttpIsAnsweredOverUdp() throws Exception {
    String zone = uniqueZone("loop");
    String zoneId = createZone(zone);
    createRecord(zoneId, "@", "A", "192.0.2.10");
    // The wildcard target is the apex of this same zone, so the chase stays in-zone and has A rows
    // to find. An out-of-zone target would answer the CNAME alone, which is a different assertion.
    createRecord(zoneId, "*", "CNAME", zone);
    String explicitId = createRecord(zoneId, "app", "A", "192.0.2.20");

    // The apex: no wildcard involved, the plainest thing this server does.
    Message apex = ask(zone, WireType.A.code());
    assertEquals(Rcode.NOERROR, apex.getHeader().getRcode());
    assertEquals(List.of("192.0.2.10"), addresses(apex));

    // The wildcard, EXPANDED: the owner of the CNAME in the answer is the queried name and never a
    // literal `*`, because a resolver discards an answer whose owner does not match its question.
    Message wildcard = ask("anything." + zone, WireType.A.code());
    assertEquals(Rcode.NOERROR, wildcard.getHeader().getRcode());
    List<Record> answers = wildcard.getSection(Section.ANSWER);
    assertEquals(2, answers.size(), "the CNAME plus the in-zone chase");
    CNAMERecord cname = (CNAMERecord) answers.getFirst();
    assertEquals("anything." + zone + ".", cname.getName().toString());
    assertEquals(zone + ".", cname.getTarget().toString());
    assertEquals(List.of("192.0.2.10"), addresses(wildcard));

    // The explicit name beats the wildcard that would otherwise cover it: the first name with ANY
    // rows wins, and later patterns are not consulted.
    Message explicit = ask("app." + zone, WireType.A.code());
    assertEquals(List.of("192.0.2.20"), addresses(explicit));
    assertTrue(
        explicit.getSection(Section.ANSWER).stream().noneMatch(r -> r instanceof CNAMERecord),
        "an explicit A must not drag the wildcard's CNAME along");

    // …and now delete it over HTTP. Same process, no restart: the next datagram gets the wildcard.
    given().when().delete("/dns/api/records/" + explicitId).then().statusCode(204);

    Message afterDelete = ask("app." + zone, WireType.A.code());
    assertEquals(2, afterDelete.getSection(Section.ANSWER).size(), "the CNAME plus its chase");
    assertEquals(List.of("192.0.2.10"), addresses(afterDelete), "the wildcard's chase, now");
    assertEquals(
        zone + ".",
        ((CNAMERecord) afterDelete.getSection(Section.ANSWER).getFirst()).getTarget().toString(),
        "the row that used to shadow the wildcard is gone and the wildcard answers");
  }

  @Test
  @Timeout(30)
  void aNameOutsideEveryConfiguredZoneIsRefused() throws Exception {
    // The real resolver's own verdict, not a scripted one: no zone is a suffix of this name, so it
    // is REFUSED rather than answered — this server recurses for nobody and holds nothing else.
    Message response = ask("nothing-here.invalid", WireType.A.code());
    assertEquals(Rcode.REFUSED, response.getHeader().getRcode());
  }

  private Message ask(String qname, int qtype) throws IOException {
    return WireClient.parse(
        WireClient.udp(server.boundPort(), WireClient.query(0x2a2a, qname + ".", qtype)));
  }

  /** Every A record's address in the answer section, in order. */
  private static List<String> addresses(Message response) {
    List<String> addresses = new ArrayList<>();
    for (Record record : response.getSection(Section.ANSWER)) {
      if (record instanceof ARecord a) {
        addresses.add(a.getAddress().getHostAddress());
      }
    }
    return addresses;
  }
}
