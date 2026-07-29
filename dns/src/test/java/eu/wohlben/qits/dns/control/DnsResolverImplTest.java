package eu.wohlben.qits.dns.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.resolve.RecordData;
import eu.wohlben.qits.dns.resolve.ResolutionResult;
import eu.wohlben.qits.dns.resolve.ResponseCode;
import eu.wohlben.qits.dns.resolve.SoaData;
import eu.wohlben.qits.dns.resolve.StoredRecord;
import eu.wohlben.qits.dns.resolve.WireType;
import eu.wohlben.qits.dns.resolve.ZoneData;
import eu.wohlben.qits.dns.resolve.ZoneSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * §3, as a checklist. Every row of the matching table, both deliberate corners, the CNAME chase in
 * all four of its endings, the two refusal families, the synthesized apex records with and without
 * configuration, and the AA bit.
 *
 * <p><b>Plain JUnit, no Quarkus, no database, no mocking library.</b> Snapshots are built here out
 * of {@link ZoneData} and {@link StoredRecord} directly, which is the whole payoff of the resolver
 * being a pure function over an immutable read model: the suite that pins the contract runs in
 * milliseconds and cannot be wrong about what the resolver was given.
 */
public class DnsResolverImplTest {

  private static final String ZONE = "qits-dev.eu";
  private static final String OTHER_ZONE = "qits.eu";
  private static final int DEFAULT_TTL = 60;
  private static final long SERIAL = 42L;

  private static final int TYPE_A = WireType.A.code();
  private static final int TYPE_AAAA = WireType.AAAA.code();
  private static final int TYPE_CNAME = WireType.CNAME.code();
  private static final int TYPE_SOA = WireType.SOA.code();
  private static final int TYPE_NS = WireType.NS.code();
  private static final int TYPE_MX = 15;
  private static final int TYPE_TXT = 16;
  private static final int TYPE_ANY = 255;
  private static final int TYPE_AXFR = 252;
  private static final int TYPE_IXFR = 251;

  private static final List<String> NS_NAMES = List.of("ns1.qits.eu", "ns2.qits.eu");

  // --- the matching table -----------------------------------------------------------------------

  @Test
  public void apexRowsAnswerTheApex() {
    ResolutionResult result = over(plain(ZONE, a("@", "10.0.0.1"))).resolve(ZONE, TYPE_A);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertEquals(1, result.answers().size());
    assertOwner(ZONE, result.answers().get(0));
    assertEquals("10.0.0.1", result.answers().get(0).value());
  }

  @Test
  public void oneLabelExplicitBeatsTheWildcard() {
    ResolutionResult result =
        over(plain(ZONE, a("feature", "10.0.0.1"), a("*", "10.9.9.9")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(List.of("10.0.0.1"), values(result));
  }

  @Test
  public void oneLabelFallsToTheWildcard() {
    ResolutionResult result =
        over(plain(ZONE, a("*", "10.9.9.9"))).resolve("anything." + ZONE, TYPE_A);

    assertEquals(List.of("10.9.9.9"), values(result));
  }

  @Test
  public void twoLabelsExplicitBeatsBothWildcards() {
    ResolutionResult result =
        over(
                plain(
                    ZONE,
                    a("app.feature", "10.0.0.1"),
                    a("*.feature", "10.0.0.2"),
                    a("*.*", "10.0.0.3")))
            .resolve("app.feature." + ZONE, TYPE_A);

    assertEquals(List.of("10.0.0.1"), values(result));
  }

  @Test
  public void twoLabelsPrefersTheSpecificWildcardOverTheGeneralOne() {
    ResolutionResult result =
        over(plain(ZONE, a("*.feature", "10.0.0.2"), a("*.*", "10.0.0.3")))
            .resolve("app.feature." + ZONE, TYPE_A);

    assertEquals(List.of("10.0.0.2"), values(result));
  }

  @Test
  public void twoLabelsFallAllTheWayToTheDoubleWildcard() {
    ResolutionResult result =
        over(plain(ZONE, a("*.*", "10.0.0.3"))).resolve("app.feature." + ZONE, TYPE_A);

    assertEquals(List.of("10.0.0.3"), values(result));
  }

  @Test
  public void threeLabelsAboveTheApexAreNxDomainEvenWithEveryWildcardConfigured() {
    // The shape grammar cannot express a name this deep, so no row and no wildcard can match one.
    ResolutionResult result =
        over(plain(ZONE, a("*", "10.0.0.1"), a("*.*", "10.0.0.2")))
            .resolve("a.b.c." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NXDOMAIN, result.rcode());
    assertTrue(result.answers().isEmpty());
  }

  @Test
  public void firstMatchWinsSoAWildcardIsNotConsultedForAMissingType() {
    // `feature` exists with AAAA only. A query for A is NODATA, NOT the `*` row's address: a name
    // that exists shadows the wildcard for every type, which is ordinary DNS.
    ResolutionResult result =
        over(plain(ZONE, aaaa("feature", "2001:db8::1"), a("*", "10.9.9.9")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertTrue(result.answers().isEmpty());
  }

  @Test
  public void firstMatchWinsAtTwoLabelsToo() {
    ResolutionResult result =
        over(plain(ZONE, aaaa("app.feature", "2001:db8::1"), a("*.feature", "10.0.0.2")))
            .resolve("app.feature." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertTrue(result.answers().isEmpty());
  }

  @Test
  public void everyMatchingRowOfTheAskedTypeIsReturned() {
    ResolutionResult result =
        over(plain(ZONE, a("feature", "10.0.0.1"), a("feature", "10.0.0.2")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(List.of("10.0.0.1", "10.0.0.2"), values(result));
  }

  // --- wildcard expansion -----------------------------------------------------------------------

  @Test
  public void aWildcardAnswerCarriesTheQueriedNameAsItsOwner() {
    // The single most load-bearing property of a wildcard answer: a record whose owner reads
    // `*.qits-dev.eu` is discarded by every resolver that asked for something else.
    ResolutionResult result =
        over(plain(ZONE, a("*", "10.9.9.9"))).resolve("anything." + ZONE, TYPE_A);

    assertEquals("anything." + ZONE, result.answers().get(0).owner());
  }

  @Test
  public void aDoubleWildcardAnswerCarriesTheQueriedNameAsItsOwner() {
    ResolutionResult result =
        over(plain(ZONE, a("*.*", "10.9.9.9"))).resolve("app.feature." + ZONE, TYPE_A);

    assertEquals("app.feature." + ZONE, result.answers().get(0).owner());
  }

  // --- the two deliberate corners ---------------------------------------------------------------

  @Test
  public void theWildcardAnswersEvenWhenTheNameIsAnEmptyNonTerminal() {
    // DELIBERATE DEVIATION FROM RFC 4592 (§3). `app.feature` exists, so real DNS would say
    // `feature` exists as an empty non-terminal and BLOCK the `*` wildcard, answering NODATA. A
    // user who inserted `*` meant "cover every one-label name", so here `*` answers.
    ResolutionResult result =
        over(plain(ZONE, a("app.feature", "10.0.0.1"), a("*", "10.9.9.9")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertEquals(List.of("10.9.9.9"), values(result));
    assertEquals("feature." + ZONE, result.answers().get(0).owner());
  }

  @Test
  public void anEmptyNonTerminalIsNoDataRatherThanNxDomain() {
    // The one RFC rule kept, and the reason: NXDOMAIN for `feature` is negative-cached for its
    // whole subtree, so a resolver would then answer `app.feature` out of that cache and the
    // configured record would stop working without anyone touching it.
    ResolutionResult result =
        over(plain(ZONE, a("app.feature", "10.0.0.1"))).resolve("feature." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertTrue(result.answers().isEmpty());
  }

  @Test
  public void aSpecificWildcardMakesItsParentAnEmptyNonTerminal() {
    ResolutionResult result =
        over(plain(ZONE, a("*.feature", "10.0.0.1"))).resolve("feature." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertTrue(result.answers().isEmpty());
  }

  @Test
  public void theDoubleWildcardMakesEveryOneLabelNameAnEmptyNonTerminal() {
    // `*.*` configures a name under every one-label name, exactly as `app.feature` does under
    // `feature` — so NXDOMAIN would poison every two-label name the `*.*` row exists to serve.
    ResolutionResult result =
        over(plain(ZONE, a("*.*", "10.0.0.1"))).resolve("feature." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertTrue(result.answers().isEmpty());
  }

  @Test
  public void anUnrelatedOneLabelNameIsStillNxDomain() {
    ResolutionResult result =
        over(plain(ZONE, a("app.feature", "10.0.0.1"))).resolve("other." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NXDOMAIN, result.rcode());
  }

  @Test
  public void anUnmatchedTwoLabelNameIsNxDomain() {
    ResolutionResult result =
        over(plain(ZONE, a("@", "10.0.0.1"))).resolve("app.feature." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NXDOMAIN, result.rcode());
  }

  @Test
  public void theApexNeverNxDomainsEvenWithNoRowsOfItsOwn() {
    // The apex IS the zone; denying it would deny the name we are authoritative for.
    ResolutionResult result =
        over(plain(ZONE, a("feature", "10.0.0.1"))).resolve(ZONE, TYPE_A);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertTrue(result.answers().isEmpty());
  }

  // --- CNAME ------------------------------------------------------------------------------------

  @Test
  public void aCnameAnswersAnAddressQuestion() {
    ResolutionResult result =
        over(plain(ZONE, cname("feature", "elsewhere.example.org")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(1, result.answers().size());
    assertEquals(WireType.CNAME, result.answers().get(0).type());
    assertEquals("elsewhere.example.org", result.answers().get(0).value());
  }

  @Test
  public void aCnameAnswersACnameQuestion() {
    ResolutionResult result =
        over(plain(ZONE, cname("feature", "elsewhere.example.org")))
            .resolve("feature." + ZONE, TYPE_CNAME);

    assertEquals(List.of("elsewhere.example.org"), values(result));
  }

  @Test
  public void aCnameAnswersEveryOtherQuestionToo() {
    DnsResolverImpl resolver = over(plain(ZONE, cname("feature", "elsewhere.example.org")));

    for (int qtype : new int[] {TYPE_AAAA, TYPE_MX, TYPE_TXT, TYPE_SOA, TYPE_NS}) {
      ResolutionResult result = resolver.resolve("feature." + ZONE, qtype);
      assertEquals(ResponseCode.NOERROR, result.rcode(), "qtype " + qtype);
      assertEquals(1, result.answers().size(), "qtype " + qtype);
      assertEquals(WireType.CNAME, result.answers().get(0).type(), "qtype " + qtype);
    }
  }

  @Test
  public void anOutOfZoneTargetIsAnsweredWithTheCnameAlone() {
    ResolutionResult result =
        over(plain(ZONE, cname("feature", "elsewhere.example.org")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(1, result.answers().size());
  }

  @Test
  public void anInZoneTargetIsChasedOnceAndItsAddressesAppended() {
    ResolutionResult result =
        over(
                plain(
                    ZONE,
                    cname("feature", "app." + ZONE),
                    a("app", "10.0.0.1"),
                    aaaa("app", "2001:db8::1")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(3, result.answers().size());
    assertEquals(WireType.CNAME, result.answers().get(0).type());
    // The appended half belongs to the TARGET's name, not the queried one.
    assertOwner("app." + ZONE, result.answers().get(1));
    assertOwner("app." + ZONE, result.answers().get(2));
    assertEquals(List.of("app." + ZONE, "10.0.0.1", "2001:db8::1"), values(result));
  }

  @Test
  public void theChaseCrossesIntoAnotherZoneWeHold() {
    ResolutionResult result =
        over(
                plain(ZONE, cname("feature", "app." + OTHER_ZONE)),
                plain(OTHER_ZONE, a("app", "10.1.1.1")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(List.of("app." + OTHER_ZONE, "10.1.1.1"), values(result));
  }

  @Test
  public void theChaseRunsTheSameTableSoItCanLandOnAWildcard() {
    ResolutionResult result =
        over(plain(ZONE, cname("feature", "app." + ZONE), a("*", "10.9.9.9")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(List.of("app." + ZONE, "10.9.9.9"), values(result));
    assertOwner("app." + ZONE, result.answers().get(1));
  }

  @Test
  public void aChaseLandingOnAnotherCnameStopsAfterOneHop() {
    // One hop means one hop: the chase appends addresses, and there are none to append here.
    ResolutionResult result =
        over(
                plain(
                    ZONE,
                    cname("feature", "app." + ZONE),
                    cname("app", "deeper.example.org")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(1, result.answers().size());
    assertEquals(List.of("app." + ZONE), values(result));
  }

  @Test
  public void theChaseCanLandOnAZonesBareApex() {
    // The one chase target with zero labels above an apex, so it takes `candidates`' `@` branch
    // rather than either wildcard branch. Nothing about the code distinguishes it, which is exactly
    // why it is worth pinning: `relativeTo` returns "" here and an off-by-one in that arithmetic
    // would surface only on this shape.
    ResolutionResult result =
        over(plain(ZONE, cname("feature", OTHER_ZONE)), plain(OTHER_ZONE, a("@", "10.2.2.2")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(List.of(OTHER_ZONE, "10.2.2.2"), values(result));
    assertOwner(OTHER_ZONE, result.answers().get(1));
  }

  @Test
  public void aChaseOntoANameWithNoRowsAppendsNothing() {
    ResolutionResult result =
        over(plain(ZONE, cname("feature", "nowhere." + ZONE))).resolve("feature." + ZONE, TYPE_A);

    assertEquals(List.of("nowhere." + ZONE), values(result));
  }

  // --- REFUSED ----------------------------------------------------------------------------------

  @Test
  public void aNameOutsideEveryZoneIsRefused() {
    ResolutionResult result = over(plain(ZONE, a("@", "10.0.0.1"))).resolve("other.tld", TYPE_A);

    assertEquals(ResponseCode.REFUSED, result.rcode());
    assertFalse(result.authoritative());
    assertTrue(result.answers().isEmpty());
    assertTrue(result.authority().isEmpty());
  }

  @Test
  public void aSuffixThatIsNotOnALabelBoundaryIsOutOfZone() {
    ResolutionResult result =
        over(plain(ZONE, a("@", "10.0.0.1"))).resolve("not" + ZONE, TYPE_A);

    assertEquals(ResponseCode.REFUSED, result.rcode());
  }

  @Test
  public void anyAxfrAndIxfrAreRefusedEvenForANameWeHold() {
    DnsResolverImpl resolver = over(delegated(ZONE, a("@", "10.0.0.1")));

    for (int qtype : new int[] {TYPE_ANY, TYPE_AXFR, TYPE_IXFR}) {
      ResolutionResult result = resolver.resolve(ZONE, qtype);
      assertEquals(ResponseCode.REFUSED, result.rcode(), "qtype " + qtype);
      assertFalse(result.authoritative(), "qtype " + qtype);
    }
  }

  // --- the synthesized apex records -------------------------------------------------------------

  @Test
  public void soaAtTheApexCarriesTheZonesSerial() {
    ResolutionResult result = over(delegated(ZONE, a("@", "10.0.0.1"))).resolve(ZONE, TYPE_SOA);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertEquals(1, result.answers().size());
    RecordData soa = result.answers().get(0);
    assertEquals(WireType.SOA, soa.type());
    assertNotNull(soa.soa());
    assertEquals(SERIAL, soa.soa().serial());
  }

  @Test
  public void soaAtTheApexCarriesTheConfiguredNamesAndTheDefaultTtlAsMinimum() {
    ResolutionResult result = over(delegated(ZONE)).resolve(ZONE, TYPE_SOA);

    SoaData soa = result.answers().get(0).soa();
    assertEquals("ns1.qits.eu", soa.mname());
    assertEquals("hostmaster.qits.eu", soa.rname());
    assertEquals(DEFAULT_TTL, soa.minimum());
    assertEquals(ZONE, result.answers().get(0).owner());
  }

  @Test
  public void nsAtTheApexListsEveryConfiguredName() {
    ResolutionResult result = over(delegated(ZONE)).resolve(ZONE, TYPE_NS);

    assertEquals(NS_NAMES, values(result));
    for (RecordData record : result.answers()) {
      assertEquals(WireType.NS, record.type());
      assertOwner(ZONE, record);
    }
  }

  @Test
  public void soaAndNsAreNoDataWhenSynthesisIsOff() {
    DnsResolverImpl resolver = over(plain(ZONE, a("@", "10.0.0.1")));

    for (int qtype : new int[] {TYPE_SOA, TYPE_NS}) {
      ResolutionResult result = resolver.resolve(ZONE, qtype);
      assertEquals(ResponseCode.NOERROR, result.rcode(), "qtype " + qtype);
      assertTrue(result.answers().isEmpty(), "qtype " + qtype);
      assertTrue(result.authority().isEmpty(), "qtype " + qtype);
    }
  }

  @Test
  public void soaAndNsBelowTheApexAreNoData() {
    DnsResolverImpl resolver = over(delegated(ZONE, a("feature", "10.0.0.1")));

    for (int qtype : new int[] {TYPE_SOA, TYPE_NS}) {
      ResolutionResult result = resolver.resolve("feature." + ZONE, qtype);
      assertEquals(ResponseCode.NOERROR, result.rcode(), "qtype " + qtype);
      assertTrue(result.answers().isEmpty(), "qtype " + qtype);
    }
  }

  // --- unserved types ---------------------------------------------------------------------------

  @Test
  public void anUnservedTypeOnAMatchedNameIsNoDataRatherThanAnError() {
    DnsResolverImpl resolver = over(delegated(ZONE, a("feature", "10.0.0.1")));

    for (int qtype : new int[] {TYPE_MX, TYPE_TXT, 33}) {
      ResolutionResult result = resolver.resolve("feature." + ZONE, qtype);
      assertEquals(ResponseCode.NOERROR, result.rcode(), "qtype " + qtype);
      assertTrue(result.answers().isEmpty(), "qtype " + qtype);
      assertEquals(1, result.authority().size(), "qtype " + qtype);
    }
  }

  @Test
  public void askingForTheOtherAddressFamilyIsNoData() {
    ResolutionResult result =
        over(delegated(ZONE, a("feature", "10.0.0.1"))).resolve("feature." + ZONE, TYPE_AAAA);

    assertEquals(ResponseCode.NOERROR, result.rcode());
    assertTrue(result.answers().isEmpty());
  }

  // --- negative answers and the authority section -----------------------------------------------

  @Test
  public void nxDomainCarriesTheZonesSoaInAuthority() {
    ResolutionResult result = over(delegated(ZONE)).resolve("nope." + ZONE, TYPE_A);

    assertEquals(ResponseCode.NXDOMAIN, result.rcode());
    assertEquals(1, result.authority().size());
    assertEquals(WireType.SOA, result.authority().get(0).type());
    assertEquals(SERIAL, result.authority().get(0).soa().serial());
  }

  @Test
  public void noDataCarriesTheZonesSoaInAuthority() {
    ResolutionResult result =
        over(delegated(ZONE, a("feature", "10.0.0.1"))).resolve("feature." + ZONE, TYPE_AAAA);

    assertEquals(1, result.authority().size());
    assertEquals(WireType.SOA, result.authority().get(0).type());
  }

  @Test
  public void negativeAnswersCarryAnEmptyAuthorityWhenSynthesisIsOff() {
    // The decision the scaffold left open: a negative answer is still GIVEN when nobody configured
    // a hostmaster address; it simply has no SOA to be negative-cached against.
    DnsResolverImpl resolver = over(plain(ZONE, a("feature", "10.0.0.1")));

    ResolutionResult nxDomain = resolver.resolve("nope." + ZONE, TYPE_A);
    assertEquals(ResponseCode.NXDOMAIN, nxDomain.rcode());
    assertTrue(nxDomain.authority().isEmpty());

    ResolutionResult noData = resolver.resolve("feature." + ZONE, TYPE_AAAA);
    assertEquals(ResponseCode.NOERROR, noData.rcode());
    assertTrue(noData.authority().isEmpty());
  }

  @Test
  public void anAnswerCarriesNoAuthoritySection() {
    ResolutionResult result =
        over(delegated(ZONE, a("feature", "10.0.0.1"))).resolve("feature." + ZONE, TYPE_A);

    assertTrue(result.authority().isEmpty());
  }

  // --- the AA bit -------------------------------------------------------------------------------

  @Test
  public void everythingDecidedOutOfOneOfOurZonesIsAuthoritative() {
    DnsResolverImpl resolver = over(delegated(ZONE, a("feature", "10.0.0.1")));

    assertTrue(resolver.resolve("feature." + ZONE, TYPE_A).authoritative(), "an answer");
    assertTrue(resolver.resolve("feature." + ZONE, TYPE_AAAA).authoritative(), "a NODATA");
    assertTrue(resolver.resolve("nope." + ZONE, TYPE_A).authoritative(), "an NXDOMAIN");
    assertTrue(resolver.resolve(ZONE, TYPE_SOA).authoritative(), "a synthesized SOA");
  }

  @Test
  public void nothingDecidedAboutTheRequestItselfIsAuthoritative() {
    DnsResolverImpl resolver = over(delegated(ZONE, a("feature", "10.0.0.1")));

    assertFalse(resolver.resolve("other.tld", TYPE_A).authoritative(), "out of zone");
    assertFalse(resolver.resolve(ZONE, TYPE_ANY).authoritative(), "ANY");
  }

  // --- TTL --------------------------------------------------------------------------------------

  @Test
  public void aRecordsOwnTtlIsWhatTheAnswerCarries() {
    ResolutionResult result =
        over(
                delegated(
                    ZONE,
                    new StoredRecord("feature", DnsRecordType.A, 5, "10.0.0.1"),
                    new StoredRecord("other", DnsRecordType.A, DEFAULT_TTL, "10.0.0.2")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(5, result.answers().get(0).ttl());
  }

  @Test
  public void theSynthesizedRecordsCarryTheZoneDefaultTtl() {
    DnsResolverImpl resolver = over(delegated(ZONE));

    assertEquals(DEFAULT_TTL, resolver.resolve(ZONE, TYPE_SOA).answers().get(0).ttl());
    assertEquals(DEFAULT_TTL, resolver.resolve(ZONE, TYPE_NS).answers().get(0).ttl());
  }

  // --- normalisation and zone selection ---------------------------------------------------------

  @Test
  public void theQnameIsLowercasedAndItsTrailingDotRemoved() {
    // The contract says the wire layer hands over a normalised name; doing it again here costs a
    // string compare and means a caller that forgot is answered rather than REFUSED.
    ResolutionResult result =
        over(plain(ZONE, a("feature", "10.0.0.1"))).resolve("FEATURE.QITS-DEV.EU.", TYPE_A);

    assertEquals(List.of("10.0.0.1"), values(result));
    assertOwner("feature." + ZONE, result.answers().get(0));
  }

  @Test
  public void theLongestMatchingZoneWins() {
    // Two zones where one contains the other cannot be created over the API — but the rule is the
    // snapshot's, so it is pinned here rather than left to the validator to imply.
    ResolutionResult result =
        over(plain("eu", a("*", "10.0.0.9")), plain(ZONE, a("feature", "10.0.0.1")))
            .resolve("feature." + ZONE, TYPE_A);

    assertEquals(List.of("10.0.0.1"), values(result));
  }

  @Test
  public void aQueryForNothingIsRefused() {
    assertEquals(ResponseCode.REFUSED, over(plain(ZONE)).resolve("", TYPE_A).rcode());
    assertEquals(ResponseCode.REFUSED, over(plain(ZONE)).resolve(null, TYPE_A).rcode());
    assertEquals(ResponseCode.REFUSED, over(plain(ZONE)).resolve(".", TYPE_A).rcode());
  }

  // --- fixtures ---------------------------------------------------------------------------------

  private static DnsResolverImpl over(ZoneData... zones) {
    ZoneSnapshotHolder holder = new ZoneSnapshotHolder();
    holder.publish(ZoneSnapshot.of(Arrays.asList(zones)));
    return new DnsResolverImpl(holder);
  }

  /** A zone with SOA/NS synthesis OFF — the shipped default, and most of this suite. */
  private static ZoneData plain(String fqdn, StoredRecord... rows) {
    return zone(fqdn, null, List.of(), rows);
  }

  /** A zone as a real delegation has it: {@code ns-names} and {@code hostmaster} both set. */
  private static ZoneData delegated(String fqdn, StoredRecord... rows) {
    SoaData soa =
        new SoaData("ns1.qits.eu", "hostmaster.qits.eu", SERIAL, 86_400, 7_200, 1_209_600,
            DEFAULT_TTL);
    return zone(fqdn, soa, NS_NAMES, rows);
  }

  private static ZoneData zone(String fqdn, SoaData soa, List<String> nsNames,
      StoredRecord... rows) {
    Map<String, List<StoredRecord>> byName = new LinkedHashMap<>();
    for (StoredRecord row : rows) {
      byName.computeIfAbsent(row.name(), key -> new ArrayList<>()).add(row);
    }
    return new ZoneData(fqdn, SERIAL, byName, Optional.ofNullable(soa), nsNames, DEFAULT_TTL);
  }

  private static StoredRecord a(String name, String value) {
    return new StoredRecord(name, DnsRecordType.A, DEFAULT_TTL, value);
  }

  private static StoredRecord aaaa(String name, String value) {
    return new StoredRecord(name, DnsRecordType.AAAA, DEFAULT_TTL, value);
  }

  private static StoredRecord cname(String name, String value) {
    return new StoredRecord(name, DnsRecordType.CNAME, DEFAULT_TTL, value);
  }

  private static List<String> values(ResolutionResult result) {
    return result.answers().stream().map(RecordData::value).toList();
  }

  private static void assertOwner(String expected, RecordData record) {
    assertEquals(expected, record.owner());
  }
}
