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
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Opcode;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Record;
import org.xbill.DNS.Section;

/**
 * The three kinds of input this server refuses, and the fact that only ONE of them gets silence.
 *
 * <p>The asymmetry is the whole point and it is a security property, not a style. Bytes that do not
 * parse are dropped, because a reply to them is free amplification: forge a victim's source
 * address, send a few bytes, and the victim receives our response at our expense. Bytes that DO
 * parse are answered — NOTIMP for an opcode we do not implement, FORMERR for a query that is not a
 * single question — because by then the message is a message, the rcode is the smallest thing we
 * can say, and silence would break clients that are merely wrong rather than hostile.
 *
 * <p>The garbage test asserts a receive TIMEOUT rather than an error response. That distinction is
 * the test: an assertion that the response "is not an answer" would pass just as happily against a
 * server that replies FORMERR to everything.
 */
@QuarkusTest
@TestProfile(ScriptedResolverProfile.class)
class DnsMalformedMessageTest {

  @Inject DnsWireServer server;

  @Inject ScriptedDnsResolver resolver;

  @BeforeEach
  void reset() {
    resolver.reset();
  }

  @Test
  @Timeout(20)
  void garbageIsDroppedAndTheSocketKeepsServing() throws Exception {
    resolver.script(
        "alive.qits-dev.eu",
        WireType.A.code(),
        ResolutionResult.answer(
            List.of(RecordData.of("alive.qits-dev.eu", WireType.A, 60, "192.0.2.9"))));

    long droppedBefore = server.droppedMessages();

    // Fixed bytes rather than a random buffer, because a random one can accidentally parse: the
    // first twelve bytes are a header, and a header whose section counts happen to be zero is a
    // perfectly valid (if pointless) message. This blob's fourth and fifth bytes read as a QDCOUNT
    // in the thousands, so the parse dies reading questions that are not there.
    byte[] garbage =
        "this is not a DNS message, not even a little".getBytes(StandardCharsets.UTF_8);
    assertTrue(
        WireClient.udpSilent(server.boundPort(), garbage),
        "an unparseable datagram must get NO reply at all — answering it is an amplifier");
    assertEquals(
        droppedBefore + 1,
        server.droppedMessages(),
        "and it must be counted, because the count is the only trace this path leaves");

    Message response =
        WireClient.parse(
            WireClient.udp(
                server.boundPort(),
                WireClient.query(0x6001, "alive.qits-dev.eu.", WireType.A.code())));
    assertEquals(
        Rcode.NOERROR,
        response.getHeader().getRcode(),
        "the listener must survive garbage; one packet cannot be allowed to silence a nameserver");
    assertEquals(1, response.getSection(Section.ANSWER).size());
  }

  @Test
  @Timeout(15)
  void anOpcodeThatIsNotQueryGetsNotimp() throws Exception {
    Message update = WireClient.query(0x6002, "alive.qits-dev.eu.", WireType.A.code());
    update.getHeader().setOpcode(Opcode.UPDATE);

    Message response = WireClient.parse(WireClient.udp(server.boundPort(), update));

    assertEquals(Rcode.NOTIMP, response.getHeader().getRcode());
    assertEquals(
        Opcode.UPDATE,
        response.getHeader().getOpcode(),
        "the opcode is echoed, as RFC 1035 has it");
    assertFalse(
        response.getHeader().getFlag(Flags.AA), "NOTIMP is about the request, not about a zone");
  }

  @Test
  @Timeout(15)
  void twoQuestionsGetFormerr() throws Exception {
    Message twoQuestions = WireClient.query(0x6003, "one.qits-dev.eu.", WireType.A.code());
    twoQuestions.addRecord(
        Record.newRecord(Name.fromString("two.qits-dev.eu."), WireType.A.code(), DClass.IN),
        Section.QUESTION);

    Message response = WireClient.parse(WireClient.udp(server.boundPort(), twoQuestions));

    assertEquals(
        Rcode.FORMERR,
        response.getHeader().getRcode(),
        "QDCOUNT != 1 parsed fine, so it is answered — this is the case FORMERR exists for");
    assertFalse(response.getHeader().getFlag(Flags.AA));
  }

  @Test
  @Timeout(15)
  void noQuestionAtAllGetsFormerr() throws Exception {
    Message noQuestion = new Message(0x6004);

    Message response = WireClient.parse(WireClient.udp(server.boundPort(), noQuestion));

    assertEquals(Rcode.FORMERR, response.getHeader().getRcode());
    assertEquals(
        0x6004, response.getHeader().getID(), "even a FORMERR carries the query's id back");
  }
}
