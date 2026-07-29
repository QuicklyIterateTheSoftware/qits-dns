package eu.wohlben.qits.dns.wire;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.resolve.RecordData;
import eu.wohlben.qits.dns.resolve.ResolutionResult;
import eu.wohlben.qits.dns.resolve.WireType;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Section;

/**
 * TCP, which an authoritative server does not get to skip: TC=1 means "ask again over TCP", so a
 * server that only speaks UDP fails every answer that did not fit and every resolver that probes.
 *
 * <p>Two things are under test and they are separable. The framing is RFC 1035 §4.2.2 — a two-byte
 * big-endian length prefix on every message in BOTH directions — and {@link
 * WireClient.Tcp#lastLengthPrefix} is read back so the assertion is on the prefix itself rather
 * than on "the bytes happened to parse". The second is connection reuse (RFC 7766): one connection
 * carries several queries, so the server's parser has to flip back to expecting a length prefix
 * after every message instead of treating a connection as one exchange.
 */
@QuarkusTest
class DnsTcpFramingTest {

  @Inject DnsWireServer server;

  @Inject ScriptedDnsResolver resolver;

  @BeforeEach
  void reset() {
    resolver.reset();
  }

  @Test
  @Timeout(15)
  void framesRepliesWithTheirOwnLengthPrefix() throws Exception {
    resolver.script(
        "one.qits-dev.eu",
        WireType.A.code(),
        ResolutionResult.answer(
            List.of(RecordData.of("one.qits-dev.eu", WireType.A, 60, "192.0.2.1"))));

    try (WireClient.Tcp tcp = new WireClient.Tcp(server.boundPort())) {
      byte[] reply = tcp.exchange(WireClient.query(0x1111, "one.qits-dev.eu.", WireType.A.code()));

      assertEquals(
          reply.length,
          tcp.lastLengthPrefix(),
          "the prefix must be the message's own length, or the next read starts mid-message");
      Message response = WireClient.parse(reply);
      assertEquals(0x1111, response.getHeader().getID());
      assertTrue(response.getHeader().getFlag(Flags.AA));
      assertEquals(1, response.getSection(Section.ANSWER).size());
    }
  }

  @Test
  @Timeout(15)
  void answersSeveralQueriesOnOneConnection() throws Exception {
    resolver.script(
        "one.qits-dev.eu",
        WireType.A.code(),
        ResolutionResult.answer(
            List.of(RecordData.of("one.qits-dev.eu", WireType.A, 60, "192.0.2.1"))));
    resolver.script(
        "two.qits-dev.eu",
        WireType.A.code(),
        ResolutionResult.answer(
            List.of(RecordData.of("two.qits-dev.eu", WireType.A, 60, "192.0.2.2"))));

    try (WireClient.Tcp tcp = new WireClient.Tcp(server.boundPort())) {
      Message first =
          WireClient.parse(
              tcp.exchange(WireClient.query(0x2222, "one.qits-dev.eu.", WireType.A.code())));
      Message second =
          WireClient.parse(
              tcp.exchange(WireClient.query(0x3333, "two.qits-dev.eu.", WireType.A.code())));

      assertEquals(0x2222, first.getHeader().getID());
      assertEquals(0x3333, second.getHeader().getID(), "the second query is answered too");
      assertEquals("one.qits-dev.eu.", first.getQuestion().getName().toString());
      assertEquals("two.qits-dev.eu.", second.getQuestion().getName().toString());
    }
  }
}
