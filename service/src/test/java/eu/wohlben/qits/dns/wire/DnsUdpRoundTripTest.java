package eu.wohlben.qits.dns.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.resolve.RecordData;
import eu.wohlben.qits.dns.resolve.ResolutionResult;
import eu.wohlben.qits.dns.resolve.WireType;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.xbill.DNS.ARecord;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Section;

/**
 * The happy path over UDP, on the ephemeral port the suite binds: a question goes in, an answer
 * comes back, and the header says the things an authoritative server's header must say.
 *
 * <p>The case-echo test at the bottom is the one worth reading. DNS matching is case-insensitive
 * but a response must repeat the question's own spelling, and getting that right costs nothing
 * PROVIDED the response is built from the parsed question object rather than from a name re-derived
 * out of zone data. It is exactly the sort of property that works by accident until somebody tidies
 * the codec, so it is asserted on both the question section and the answer's owner name.
 */
@QuarkusTest
@TestProfile(ScriptedResolverProfile.class)
class DnsUdpRoundTripTest {

  @Inject DnsWireServer server;

  @Inject ScriptedDnsResolver resolver;

  @BeforeEach
  void reset() {
    resolver.reset();
  }

  @Test
  @Timeout(15)
  void answersAQuestionAndSaysSoInTheHeader() throws Exception {
    resolver.script(
        "app.feature.qits-dev.eu",
        WireType.A.code(),
        ResolutionResult.answer(
            List.of(RecordData.of("app.feature.qits-dev.eu", WireType.A, 60, "192.0.2.7"))));

    Message response =
        WireClient.parse(
            WireClient.udp(
                server.boundPort(),
                WireClient.query(0x4711, "app.feature.qits-dev.eu.", WireType.A.code())));

    assertEquals(0x4711, response.getHeader().getID(), "the response must carry the query's id");
    assertTrue(response.getHeader().getFlag(Flags.QR), "QR");
    assertTrue(response.getHeader().getFlag(Flags.AA), "AA, for a name out of one of our zones");
    assertFalse(
        response.getHeader().getFlag(Flags.RA),
        "RA must never be set — this server recurses for nobody, and saying otherwise invites use "
            + "as somebody else's resolver");
    assertFalse(response.getHeader().getFlag(Flags.TC), "nothing to truncate");
    assertEquals(Rcode.NOERROR, response.getHeader().getRcode());

    assertEquals(
        "app.feature.qits-dev.eu.",
        response.getQuestion().getName().toString(),
        "the question is echoed");
    assertEquals(WireType.A.code(), response.getQuestion().getType());

    List<org.xbill.DNS.Record> answers = response.getSection(Section.ANSWER);
    assertEquals(1, answers.size());
    ARecord answer = (ARecord) answers.getFirst();
    assertEquals("192.0.2.7", answer.getAddress().getHostAddress());
    assertEquals(60, answer.getTTL());
  }

  @Test
  @Timeout(15)
  void refusesWhatTheResolverRefuses() throws Exception {
    Message response =
        WireClient.parse(
            WireClient.udp(
                server.boundPort(), WireClient.query(0x0001, "somebody.else.", WireType.A.code())));

    assertEquals(Rcode.REFUSED, response.getHeader().getRcode());
    assertFalse(
        response.getHeader().getFlag(Flags.AA),
        "REFUSED is a statement about the request, not about a zone we hold");
    assertTrue(response.getSection(Section.ANSWER).isEmpty());
  }

  @Test
  @Timeout(15)
  void echoesTheQuestionsExactCapitalisation() throws Exception {
    // The resolver is handed the lowercased name — that is the contract — and answers with the
    // canonical owner. The querier's spelling has to survive anyway, which it does only because the
    // response is assembled around the ORIGINAL question record.
    resolver.script(
        "test.example.com",
        WireType.A.code(),
        ResolutionResult.answer(
            List.of(RecordData.of("test.example.com", WireType.A, 60, "192.0.2.1"))));

    Message response =
        WireClient.parse(
            WireClient.udp(
                server.boundPort(),
                WireClient.query(0x00ff, "TeSt.ExAmPle.CoM.", WireType.A.code())));

    assertEquals(
        "TeSt.ExAmPle.CoM.",
        response.getQuestion().getName().toString(),
        "the question section must repeat the querier's own spelling");
    assertEquals(
        "TeSt.ExAmPle.CoM.",
        response.getSection(Section.ANSWER).getFirst().getName().toString(),
        "and so must the answer's owner name — a resolver compares the two");
  }
}
