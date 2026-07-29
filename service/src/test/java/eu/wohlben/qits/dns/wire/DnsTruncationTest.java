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
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Section;

/**
 * Truncation, and the one dnsjava behaviour most likely to be mistaken for a bug.
 *
 * <p>{@code Message.toWire(maxLength)} is <b>RRset-atomic</b>: on overflow it rolls back to the
 * START of the RRset it was writing, so a partial RRset is never emitted — and an RRset that alone
 * exceeds the budget is dropped ENTIRELY. Sixty A records sharing one owner are one RRset, so a
 * 512-byte UDP response carries ZERO of them and TC=1, not "as many as fit". That is correct: a
 * resolver handed half an RRset would cache half an answer, whereas TC=1 sends it to TCP where the
 * whole set is there. It is also the shape this server hits most often, since many A rows under one
 * hostname is exactly what a round-robin deployment looks like.
 *
 * <p>The distinct-owner case is included beside it because it is the one that behaves the way
 * people expect, and having both in the same file is what stops the zero-answer assertion below
 * from reading like a workaround.
 *
 * <p>Both then go over TCP, which is what TC=1 instructs, and come back whole.
 */
@QuarkusTest
@TestProfile(ScriptedResolverProfile.class)
class DnsTruncationTest {

  /** Enough A records that the set cannot fit in 512 bytes by any encoding. */
  private static final int RECORDS = 60;

  @Inject DnsWireServer server;

  @Inject ScriptedDnsResolver resolver;

  @BeforeEach
  void reset() {
    resolver.reset();
  }

  private static List<RecordData> sameOwner() {
    List<RecordData> records = new ArrayList<>();
    for (int i = 0; i < RECORDS; i++) {
      records.add(RecordData.of("big.qits-dev.eu", WireType.A, 60, "10.0.0." + i));
    }
    return records;
  }

  private static List<RecordData> distinctOwners() {
    List<RecordData> records = new ArrayList<>();
    for (int i = 0; i < RECORDS; i++) {
      records.add(RecordData.of("h" + i + ".qits-dev.eu", WireType.A, 60, "10.0.1." + i));
    }
    return records;
  }

  @Test
  @Timeout(15)
  void oneOversizedRRsetTruncatesToNoAnswersAtAll() throws Exception {
    resolver.script("big.qits-dev.eu", WireType.A.code(), ResolutionResult.answer(sameOwner()));

    byte[] wire =
        WireClient.udp(
            server.boundPort(), WireClient.query(0x5001, "big.qits-dev.eu.", WireType.A.code()));
    Message response = WireClient.parse(wire);

    assertTrue(response.getHeader().getFlag(Flags.TC), "TC=1 is what sends the resolver to TCP");
    assertTrue(
        wire.length <= DnsWireServer.UDP_BUDGET_WITHOUT_EDNS,
        "a query with no OPT gets the RFC 1035 512-byte budget");
    assertTrue(
        response.getSection(Section.ANSWER).isEmpty(),
        "ZERO answers, not as many as fit: truncation is RRset-atomic and these records share one "
            + "owner, so dnsjava drops the whole set rather than emit part of it. This is dnsjava "
            + "behaving correctly, not a bug — half an RRset is a wrong answer, TC=1 is not.");
  }

  @Test
  @Timeout(15)
  void manyRRsetsTruncateToTheOnesThatFit() throws Exception {
    resolver.script(
        "many.qits-dev.eu", WireType.A.code(), ResolutionResult.answer(distinctOwners()));

    byte[] wire =
        WireClient.udp(
            server.boundPort(), WireClient.query(0x5002, "many.qits-dev.eu.", WireType.A.code()));
    Message response = WireClient.parse(wire);
    int answers = response.getSection(Section.ANSWER).size();

    assertTrue(response.getHeader().getFlag(Flags.TC), "TC=1");
    assertTrue(wire.length <= DnsWireServer.UDP_BUDGET_WITHOUT_EDNS, "within the 512-byte budget");
    assertTrue(answers > 0, "distinct owners are separate RRsets, so whole ones do fit");
    assertTrue(answers < RECORDS, "but not all of them, or nothing was truncated");
  }

  @Test
  @Timeout(15)
  void theSameQueryOverTcpReturnsTheWholeAnswer() throws Exception {
    resolver.script("big.qits-dev.eu", WireType.A.code(), ResolutionResult.answer(sameOwner()));

    try (WireClient.Tcp tcp = new WireClient.Tcp(server.boundPort())) {
      Message response =
          WireClient.parse(
              tcp.exchange(WireClient.query(0x5003, "big.qits-dev.eu.", WireType.A.code())));

      assertFalse(response.getHeader().getFlag(Flags.TC), "nothing to truncate over TCP");
      assertEquals(
          RECORDS,
          response.getSection(Section.ANSWER).size(),
          "the retry TC=1 asked for has to actually produce the whole set");
    }
  }
}
