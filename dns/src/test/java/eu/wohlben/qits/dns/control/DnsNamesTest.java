package eu.wohlben.qits.dns.control;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.control.DnsNames.ExistingRecord;
import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.error.BadRequestException;
import eu.wohlben.qits.dns.error.ConflictException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The validation register, exhaustively: every accepted shape and every rejection.
 *
 * <p>Plain JUnit — these are pure predicates over strings, and the point of implementing them once
 * in a framework-free class is that the suite pinning them needs nothing to run.
 *
 * <p>The rejection tests assert the exception TYPE as much as the rejection itself: 400 and 409 are
 * different answers to a caller, and which one a rule produces ("your payload is malformed" versus
 * "this zone cannot hold that") is part of the contract rather than an implementation detail.
 */
public class DnsNamesTest {

  // --- zone fqdn --------------------------------------------------------------------------------

  @Test
  public void acceptsARegisteredDomain() {
    assertEquals("qits-dev.eu", DnsNames.requireZoneFqdn("qits-dev.eu"));
    assertEquals("a.b", DnsNames.requireZoneFqdn("a.b"));
    assertEquals("x9-y.co.uk", DnsNames.requireZoneFqdn("x9-y.co.uk"));
    assertEquals("qits.eu", DnsNames.requireZoneFqdn("  qits.eu  "));
    assertEquals("a".repeat(63) + ".eu", DnsNames.requireZoneFqdn("a".repeat(63) + ".eu"));
  }

  @Test
  public void rejectsAZoneOfOneLabel() {
    // A zone is 'a.b', never a bare TLD — a single-label zone here is always a typo, and accepting
    // one makes every name under that TLD our problem instead of REFUSED.
    assertThrows(BadRequestException.class, () -> DnsNames.requireZoneFqdn("eu"));
  }

  @Test
  public void rejectsAZoneWithATrailingDot() {
    assertThrows(BadRequestException.class, () -> DnsNames.requireZoneFqdn("qits-dev.eu."));
  }

  @Test
  public void rejectsAZoneThatIsNotLowercase() {
    assertThrows(BadRequestException.class, () -> DnsNames.requireZoneFqdn("QITS-dev.eu"));
  }

  @Test
  public void rejectsMalformedZoneLabels() {
    for (String fqdn :
        List.of(
            "-qits.eu",
            "qits-.eu",
            "qits..eu",
            ".qits.eu",
            "qits_dev.eu",
            "qits dev.eu",
            "*.qits.eu",
            "@.eu",
            "a".repeat(64) + ".eu")) {
      assertThrows(BadRequestException.class, () -> DnsNames.requireZoneFqdn(fqdn), fqdn);
    }
  }

  @Test
  public void rejectsAZoneLongerThanTheProtocolAllows() {
    String tooLong = ("a".repeat(63) + ".").repeat(4) + "eu";
    assertThrows(BadRequestException.class, () -> DnsNames.requireZoneFqdn(tooLong));
  }

  @Test
  public void rejectsAMissingZoneFqdn() {
    assertThrows(BadRequestException.class, () -> DnsNames.requireZoneFqdn(null));
    assertThrows(BadRequestException.class, () -> DnsNames.requireZoneFqdn("  "));
  }

  // --- zone overlap -----------------------------------------------------------------------------

  @Test
  public void rejectsAZoneThatAlreadyExists() {
    assertThrows(
        ConflictException.class,
        () -> DnsNames.requireZoneAvailable("qits-dev.eu", List.of("qits-dev.eu")));
  }

  @Test
  public void rejectsAZoneInsideAConfiguredOne() {
    assertThrows(
        ConflictException.class,
        () -> DnsNames.requireZoneAvailable("a.qits-dev.eu", List.of("qits-dev.eu")));
  }

  @Test
  public void rejectsAZoneThatWouldContainAConfiguredOne() {
    // The same rule seen from the other side: whichever arrives second is refused.
    assertThrows(
        ConflictException.class,
        () -> DnsNames.requireZoneAvailable("qits-dev.eu", List.of("a.qits-dev.eu")));
  }

  @Test
  public void acceptsZonesThatMerelyShareASuffixSpelling() {
    // The overlap is at a LABEL BOUNDARY; a plain endsWith would call these related.
    assertDoesNotThrow(
        () -> DnsNames.requireZoneAvailable("notqits-dev.eu", List.of("qits-dev.eu")));
    assertDoesNotThrow(() -> DnsNames.requireZoneAvailable("qits.eu", List.of("qits-dev.eu")));
    assertDoesNotThrow(() -> DnsNames.requireZoneAvailable("qits-dev.eu", List.of()));
  }

  // --- record names -----------------------------------------------------------------------------

  @Test
  public void acceptsTheSixShapesAndNothingElse() {
    for (String name : List.of("@", "feature", "x9-y", "app.feature", "*", "*.feature", "*.*")) {
      assertEquals(name, DnsNames.requireRecordName(name), name);
    }
  }

  @Test
  public void rejectsEverySeventhShape() {
    for (String name :
        List.of(
            "*.*.feature", // three labels: deeper than the matching table goes
            "app.*", // a wildcard only ever matches leftmost
            "app.feature.x", // three labels
            ".",
            "app.",
            ".app",
            "app..feature",
            "@.feature",
            "app.@",
            "**",
            "APP",
            "-app",
            "app-",
            "app_1",
            "a".repeat(64))) {
      assertThrows(BadRequestException.class, () -> DnsNames.requireRecordName(name), name);
    }
  }

  @Test
  public void rejectsAMissingRecordName() {
    assertThrows(BadRequestException.class, () -> DnsNames.requireRecordName(null));
    assertThrows(BadRequestException.class, () -> DnsNames.requireRecordName(" "));
  }

  // --- values -----------------------------------------------------------------------------------

  @Test
  public void acceptsIpv4Literals() {
    for (String value : List.of("10.0.0.1", "0.0.0.0", "255.255.255.255", "1.2.3.4")) {
      assertEquals(value, DnsNames.requireValue(DnsRecordType.A, value), value);
    }
  }

  @Test
  public void rejectsAnythingThatIsNotADottedQuad() {
    for (String value :
        List.of(
            "10.0.0.256",
            "10.0.0",
            "10.0.0.1.2",
            "010.0.0.1", // a leading zero is octal to the C library and decimal here — refuse both
            "0x0a000001",
            "10.0.0.-1",
            "10.0.0.a",
            "10.0.0.",
            "::1",
            "localhost")) {
      assertThrows(
          BadRequestException.class, () -> DnsNames.requireValue(DnsRecordType.A, value), value);
    }
  }

  @Test
  public void acceptsIpv6Literals() {
    for (String value :
        List.of(
            "::",
            "::1",
            "2001:db8::1",
            "fe80::1",
            "1:2:3:4:5:6:7:8",
            "2001:0db8:0000:0000:0000:0000:0000:0001",
            "::ffff:10.0.0.1",
            "2001:db8::10.0.0.1",
            "1:2:3:4:5:6::")) {
      assertEquals(value, DnsNames.requireValue(DnsRecordType.AAAA, value), value);
    }
  }

  @Test
  public void rejectsAnythingThatIsNotAnIpv6Literal() {
    for (String value :
        List.of(
            "2001:db8::1%eth0", // a scope id means something only on the host that wrote it
            "1:2:3:4:5:6:7:8:9",
            "1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:8::",
            ":::",
            "1::2::3",
            ":1:2:3:4:5:6:7",
            "1:2:3:4:5:6:7:",
            "gggg::1",
            "12345::1",
            "10.0.0.1",
            "::ffff:10.0.0.256",
            "hello")) {
      assertThrows(
          BadRequestException.class, () -> DnsNames.requireValue(DnsRecordType.AAAA, value), value);
    }
  }

  @Test
  public void acceptsACnameTargetAndStripsItsTrailingDot() {
    assertEquals("app.qits.eu", DnsNames.requireValue(DnsRecordType.CNAME, "app.qits.eu"));
    assertEquals("app.qits.eu", DnsNames.requireValue(DnsRecordType.CNAME, "app.qits.eu."));
    assertEquals("qits.eu", DnsNames.requireValue(DnsRecordType.CNAME, " qits.eu "));
  }

  @Test
  public void rejectsACnameTargetThatIsNotAnAbsoluteHostname() {
    for (String value : List.of("app", "app.", ".", "*.qits.eu", "APP.qits.eu", "app..qits.eu")) {
      assertThrows(
          BadRequestException.class,
          () -> DnsNames.requireValue(DnsRecordType.CNAME, value),
          value);
    }
  }

  @Test
  public void rejectsAMissingTypeOrValue() {
    assertThrows(BadRequestException.class, () -> DnsNames.requireValue(null, "10.0.0.1"));
    assertThrows(BadRequestException.class, () -> DnsNames.requireValue(DnsRecordType.A, null));
    assertThrows(BadRequestException.class, () -> DnsNames.requireValue(DnsRecordType.A, " "));
  }

  // --- ttl --------------------------------------------------------------------------------------

  @Test
  public void acceptsAnAbsentZeroOrPositiveTtl() {
    assertEquals(null, DnsNames.requireTtl(null));
    assertEquals(0, DnsNames.requireTtl(0));
    assertEquals(300, DnsNames.requireTtl(300));
  }

  @Test
  public void rejectsANegativeTtl() {
    assertThrows(BadRequestException.class, () -> DnsNames.requireTtl(-1));
  }

  // --- adding a row -----------------------------------------------------------------------------

  @Test
  public void acceptsASecondAddressAtTheSameName() {
    assertDoesNotThrow(
        () ->
            DnsNames.requireAddable(
                "feature", DnsRecordType.A, "10.0.0.2", List.of(a("10.0.0.1"))));
  }

  @Test
  public void acceptsBothAddressFamiliesAtTheSameName() {
    assertDoesNotThrow(
        () ->
            DnsNames.requireAddable(
                "feature", DnsRecordType.AAAA, "2001:db8::1", List.of(a("10.0.0.1"))));
  }

  @Test
  public void rejectsADuplicateRow() {
    assertThrows(
        ConflictException.class,
        () ->
            DnsNames.requireAddable(
                "feature", DnsRecordType.A, "10.0.0.1", List.of(a("10.0.0.1"))));
  }

  @Test
  public void rejectsACnameAtTheApexAndSaysWhatToDoInstead() {
    ConflictException thrown =
        assertThrows(
            ConflictException.class,
            () -> DnsNames.requireAddable("@", DnsRecordType.CNAME, "app.qits.eu", List.of()));
    // The caller who hits this wanted something reasonable; the message carries the fix.
    assertTrue(thrown.getMessage().contains("A/AAAA"), thrown.getMessage());
  }

  @Test
  public void rejectsACnameBesideAnythingElse() {
    assertThrows(
        ConflictException.class,
        () ->
            DnsNames.requireAddable(
                "feature", DnsRecordType.CNAME, "app.qits.eu", List.of(a("10.0.0.1"))));
  }

  @Test
  public void rejectsAnythingElseBesideACname() {
    assertThrows(
        ConflictException.class,
        () ->
            DnsNames.requireAddable(
                "feature", DnsRecordType.A, "10.0.0.1", List.of(cname("app.qits.eu"))));
  }

  @Test
  public void rejectsASecondCnameAtOneName() {
    assertThrows(
        ConflictException.class,
        () ->
            DnsNames.requireAddable(
                "feature", DnsRecordType.CNAME, "other.qits.eu", List.of(cname("app.qits.eu"))));
  }

  // --- replacing a set --------------------------------------------------------------------------

  @Test
  public void acceptsAReplaceOverTheRowsItIsReplacing() {
    // The same-type rows are the ones going away, so they never conflict — which is what makes a
    // re-deploy sending the identical body a no-op rather than a 409.
    assertDoesNotThrow(
        () ->
            DnsNames.requireReplaceable(
                "feature",
                DnsRecordType.A,
                List.of("10.0.0.1", "10.0.0.2"),
                List.of(a("10.0.0.1"))));
  }

  @Test
  public void acceptsAReplaceBesideTheOtherAddressFamily() {
    assertDoesNotThrow(
        () ->
            DnsNames.requireReplaceable(
                "feature", DnsRecordType.A, List.of("10.0.0.1"), List.of(aaaa("2001:db8::1"))));
  }

  @Test
  public void acceptsReplacingACnameWithAnother() {
    assertDoesNotThrow(
        () ->
            DnsNames.requireReplaceable(
                "feature",
                DnsRecordType.CNAME,
                List.of("other.qits.eu"),
                List.of(cname("app.qits.eu"))));
  }

  @Test
  public void rejectsAnEmptyReplace() {
    assertThrows(
        BadRequestException.class,
        () -> DnsNames.requireReplaceable("feature", DnsRecordType.A, List.of(), List.of()));
    assertThrows(
        BadRequestException.class,
        () -> DnsNames.requireReplaceable("feature", DnsRecordType.A, null, List.of()));
  }

  @Test
  public void rejectsARepeatedValueInAReplace() {
    assertThrows(
        BadRequestException.class,
        () ->
            DnsNames.requireReplaceable(
                "feature", DnsRecordType.A, List.of("10.0.0.1", "10.0.0.1"), List.of()));
  }

  @Test
  public void rejectsAReplaceThatWouldPutTwoCnamesAtOneName() {
    assertThrows(
        ConflictException.class,
        () ->
            DnsNames.requireReplaceable(
                "feature",
                DnsRecordType.CNAME,
                List.of("a.qits.eu", "b.qits.eu"),
                List.of()));
  }

  @Test
  public void rejectsAReplaceThatWouldPutACnameBesideAnAddress() {
    assertThrows(
        ConflictException.class,
        () ->
            DnsNames.requireReplaceable(
                "feature", DnsRecordType.CNAME, List.of("a.qits.eu"), List.of(a("10.0.0.1"))));
  }

  @Test
  public void rejectsAReplaceThatWouldPutAnAddressBesideACname() {
    assertThrows(
        ConflictException.class,
        () ->
            DnsNames.requireReplaceable(
                "feature",
                DnsRecordType.A,
                List.of("10.0.0.1"),
                List.of(cname("app.qits.eu"))));
  }

  @Test
  public void rejectsAReplacePuttingACnameAtTheApex() {
    assertThrows(
        ConflictException.class,
        () ->
            DnsNames.requireReplaceable("@", DnsRecordType.CNAME, List.of("app.qits.eu"),
                List.of()));
  }

  private static ExistingRecord a(String value) {
    return new ExistingRecord(DnsRecordType.A, value);
  }

  private static ExistingRecord aaaa(String value) {
    return new ExistingRecord(DnsRecordType.AAAA, value);
  }

  private static ExistingRecord cname(String value) {
    return new ExistingRecord(DnsRecordType.CNAME, value);
  }
}
