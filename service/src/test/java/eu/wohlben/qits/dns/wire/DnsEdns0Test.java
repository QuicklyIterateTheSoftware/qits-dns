package eu.wohlben.qits.dns.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.resolve.RecordData;
import eu.wohlben.qits.dns.resolve.ResolutionResult;
import eu.wohlben.qits.dns.resolve.WireType;
import io.quarkus.test.junit.QuarkusTest;
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
 * EDNS0: the response budget, and the OPT record that negotiates it.
 *
 * <p>Three sizes are asserted rather than one, because "the budget is honoured" is a claim about a
 * min() and a single case cannot distinguish it from a constant. A query with no OPT gets the RFC
 * 1035 512 bytes; one advertising 1232 gets an answer that would not have fit in 512; one
 * advertising a small size gets that size and not our ceiling.
 *
 * <p>The answer is thirty A records under DISTINCT owners on purpose. Under one owner they would be
 * a single RRset and the 512-byte case would truncate to zero answers — correct, tested next door
 * in {@link DnsTruncationTest}, and useless here, where the interesting quantity is how many bytes
 * came back.
 */
@QuarkusTest
class DnsEdns0Test {

  private static final int RECORDS = 30;

  @Inject DnsWireServer server;

  @Inject ScriptedDnsResolver resolver;

  @BeforeEach
  void reset() {
    resolver.reset();
    List<RecordData> records = new ArrayList<>();
    for (int i = 0; i < RECORDS; i++) {
      records.add(RecordData.of("e" + i + ".qits-dev.eu", WireType.A, 60, "10.0.2." + i));
    }
    resolver.script("edns.qits-dev.eu", WireType.A.code(), ResolutionResult.answer(records));
  }

  @Test
  @Timeout(15)
  void aQueryWithOptGetsAnOptBackAndTheLargerBudget() throws Exception {
    byte[] wire =
        WireClient.udp(
            server.boundPort(),
            WireClient.queryWithEdns(
                0x7001, "edns.qits-dev.eu.", WireType.A.code(), DnsWireServer.UDP_BUDGET_CEILING));
    Message response = WireClient.parse(wire);

    assertNotNull(response.getOPT(), "a query that carried an OPT must be answered with one");
    assertEquals(
        DnsjavaCodec.ADVERTISED_PAYLOAD_SIZE,
        response.getOPT().getPayloadSize(),
        "the OPT in a RESPONSE states what THIS server can receive, not what the client claimed");
    assertTrue(
        wire.length > DnsWireServer.UDP_BUDGET_WITHOUT_EDNS,
        "the whole point: this answer does not fit in 512 and EDNS0 is what let it through");
    assertTrue(wire.length <= DnsWireServer.UDP_BUDGET_CEILING, "and never past the 1232 ceiling");
    assertFalse(response.getHeader().getFlag(Flags.TC), "it fit, so nothing was truncated");
    assertEquals(RECORDS, response.getSection(Section.ANSWER).size());
  }

  @Test
  @Timeout(15)
  void aQueryWithoutOptGetsNoOptAndFiveTwelve() throws Exception {
    byte[] wire =
        WireClient.udp(
            server.boundPort(), WireClient.query(0x7002, "edns.qits-dev.eu.", WireType.A.code()));
    Message response = WireClient.parse(wire);

    assertNull(
        response.getOPT(),
        "an OPT nobody asked for would be EDNS0 arriving unnegotiated, which pre-EDNS0 clients "
            + "have every right to choke on");
    assertTrue(wire.length <= DnsWireServer.UDP_BUDGET_WITHOUT_EDNS, "512 flat");
    assertTrue(response.getHeader().getFlag(Flags.TC), "so the same answer truncates");
  }

  @Test
  @Timeout(15)
  void aSmallAdvertisedSizeIsTheBudget() throws Exception {
    int advertised = 600;

    byte[] wire =
        WireClient.udp(
            server.boundPort(),
            WireClient.queryWithEdns(0x7003, "edns.qits-dev.eu.", WireType.A.code(), advertised));
    Message response = WireClient.parse(wire);

    assertTrue(
        wire.length <= advertised,
        "min(advertised, 1232) — a client that says 600 gets 600, not our ceiling");
    assertTrue(
        wire.length > DnsWireServer.UDP_BUDGET_WITHOUT_EDNS,
        "and not 512 either, or the advertised size was ignored in the other direction");
    assertTrue(response.getHeader().getFlag(Flags.TC));
    assertNotNull(response.getOPT(), "an OPT survives truncation — dnsjava reserves its bytes");
  }
}
