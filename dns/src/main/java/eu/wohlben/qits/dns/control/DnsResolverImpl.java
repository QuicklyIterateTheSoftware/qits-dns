package eu.wohlben.qits.dns.control;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.resolve.DnsResolver;
import eu.wohlben.qits.dns.resolve.RecordData;
import eu.wohlben.qits.dns.resolve.ResolutionResult;
import eu.wohlben.qits.dns.resolve.SoaData;
import eu.wohlben.qits.dns.resolve.StoredRecord;
import eu.wohlben.qits.dns.resolve.WireType;
import eu.wohlben.qits.dns.resolve.ZoneData;
import eu.wohlben.qits.dns.resolve.ZoneSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The whole resolution contract, and the reason this module has no framework in it. Every rule
 * lives here as a pure function over one immutable {@link ZoneSnapshot}: no socket, no datasource,
 * no clock, nothing to fake. The wire layer turns the {@link ResolutionResult} into bytes and
 * decides nothing.
 *
 * <p>The class reads {@link ZoneSnapshotHolder#current()} exactly once per query and then works
 * against that reference, so a rebuild landing mid-resolution cannot produce an answer assembled
 * half from one snapshot and half from the next.
 *
 * <p>Matching, in order:
 *
 * <table>
 *   <caption>the table §3 fixes</caption>
 *   <tr><th>labels above the apex</th><th>names tried, in order</th></tr>
 *   <tr><td>0</td><td>{@code @}</td></tr>
 *   <tr><td>1 ({@code x})</td><td>{@code x}, then {@code *}</td></tr>
 *   <tr><td>2 ({@code y.x})</td><td>{@code y.x}, then {@code *.x}, then {@code *.*}</td></tr>
 *   <tr><td>3 or more</td><td>nothing — NXDOMAIN</td></tr>
 * </table>
 *
 * <p>The first name with ANY rows wins and later patterns are not consulted — including when the
 * winner has no rows of the asked type, which is a NODATA rather than a reason to fall through to a
 * wildcard. That is ordinary DNS: a name that exists shadows the wildcard for every type.
 *
 * <p>Two corners are deliberate and both are commented at the line that implements them: the RFC
 * 4592 empty-non-terminal deviation in {@link #candidates}, and the one RFC rule kept in {@link
 * #isEmptyNonTerminal}. They pull in opposite directions on purpose — the first is about what a
 * user who typed {@code *} meant, the second is about what a resolver will cache.
 */
@ApplicationScoped
public class DnsResolverImpl implements DnsResolver {

  /**
   * The qtypes this server has a policy against rather than an implementation of. They have no
   * {@link WireType} member, so nothing downstream could serve them by accident; they are named
   * here because REFUSED and NODATA are different answers and the difference is a decision.
   *
   * <p>ANY is refused in the spirit of RFC 8482: it is the one qtype whose response size is
   * unbounded by the question, which makes it the amplification lever, and no resolver needs it.
   * AXFR/IXFR are refused because there are no secondaries — a zone transfer to a stranger is the
   * entire zone handed over for the asking.
   */
  private static final int TYPE_ANY = 255;

  private static final int TYPE_AXFR = 252;
  private static final int TYPE_IXFR = 251;

  private final ZoneSnapshotHolder snapshots;

  /**
   * Constructor injection rather than the {@code @Inject} field the rest of the repo uses, and for
   * one reason: the §3 suite constructs this class directly against a hand-built snapshot, with no
   * CDI container anywhere. A rule set that can only be exercised through a container is a rule set
   * whose tests are slower than the rules deserve.
   */
  @Inject
  public DnsResolverImpl(ZoneSnapshotHolder snapshots) {
    this.snapshots = snapshots;
  }

  @Override
  public ResolutionResult resolve(String qname, int qtype) {
    // Before anything else, including the zone lookup: these are refused whether or not we hold the
    // name, so there is nothing to learn from looking it up first.
    if (qtype == TYPE_ANY || qtype == TYPE_AXFR || qtype == TYPE_IXFR) {
      return ResolutionResult.refused();
    }

    String name = normalise(qname);
    ZoneSnapshot snapshot = snapshots.current();
    ZoneData zone = snapshot.zoneFor(name).orElse(null);
    if (zone == null) {
      return ResolutionResult.refused();
    }

    String relative = relativeTo(name, zone.fqdn());
    WireType asked = WireType.fromCode(qtype).orElse(null);

    // SOA and NS at the apex come from configuration and are never rows, so they are answered
    // before the row match rather than through it.
    if (relative.isEmpty() && (asked == WireType.SOA || asked == WireType.NS)) {
      return apexSynthesized(zone, asked);
    }

    List<StoredRecord> rows = match(zone, relative).orElse(null);
    if (rows == null) {
      return negative(zone, relative);
    }

    StoredRecord cname = firstOfType(rows, DnsRecordType.CNAME);
    if (cname != null) {
      // Regardless of qtype, CNAME included: the record IS the answer for this name, and a resolver
      // that asked for something else follows it rather than being told the name has no data.
      return answerCname(snapshot, name, cname);
    }

    if (asked == null) {
      // MX, TXT, SRV, and every other type this server does not serve. NODATA, not an error: the
      // name exists, we simply hold nothing of that type, and saying so lets the resolver cache the
      // absence instead of asking again.
      return ResolutionResult.noData(soaRecord(zone));
    }

    List<RecordData> answers = new ArrayList<>();
    for (StoredRecord row : rows) {
      if (WireType.of(row.type()) == asked) {
        // The owner is the QUERIED name. When `rows` came out of a wildcard this is the expansion,
        // and it is the whole reason a wildcard row can be served at all: an answer whose owner
        // reads `*.qits-dev.eu` is discarded by every resolver that asked for something else.
        answers.add(RecordData.of(name, asked, row.ttl(), row.value()));
      }
    }
    return answers.isEmpty()
        ? ResolutionResult.noData(soaRecord(zone))
        : ResolutionResult.answer(answers);
  }

  /**
   * The synthesized apex records. Both are NODATA when {@code qits.dns.ns-names} or {@code
   * qits.dns.hostmaster} is unset — the shipped default — because there is then nothing true to say
   * and inventing a nameserver hostname would be worse than saying nothing.
   */
  private ResolutionResult apexSynthesized(ZoneData zone, WireType asked) {
    if (asked == WireType.SOA) {
      // soaRecord is null exactly when synthesis is off, which is also when the authority section
      // of the resulting NODATA has nothing to carry — one absence, expressed once.
      RecordData soa = soaRecord(zone);
      return soa == null ? ResolutionResult.noData(null) : ResolutionResult.answer(List.of(soa));
    }
    if (zone.nsNames().isEmpty()) {
      return ResolutionResult.noData(soaRecord(zone));
    }
    List<RecordData> answers = new ArrayList<>();
    for (String ns : zone.nsNames()) {
      answers.add(RecordData.of(zone.fqdn(), WireType.NS, zone.defaultTtl(), ns));
    }
    return ResolutionResult.answer(answers);
  }

  /**
   * The CNAME, plus at most one hop of chase.
   *
   * <p>The chase runs the same matching table in the target's own zone, so a target may land on a
   * wildcard and be expanded like any other name. It stops at the first hop in both senses: a
   * target outside our zones gets no chase at all (the resolver has to ask someone else anyway, and
   * guessing on its behalf is how a cache serves an address we are not authoritative for), and a
   * target that is ITSELF a CNAME appends nothing. The names here are at most two labels deep, so a
   * chain worth walking further does not exist; a loop is impossible for the same reason.
   */
  private ResolutionResult answerCname(ZoneSnapshot snapshot, String qname, StoredRecord cname) {
    List<RecordData> answers = new ArrayList<>();
    answers.add(RecordData.of(qname, WireType.CNAME, cname.ttl(), cname.value()));

    String target = cname.value();
    ZoneData targetZone = snapshot.zoneFor(target).orElse(null);
    if (targetZone == null) {
      return ResolutionResult.answer(answers);
    }
    List<StoredRecord> rows = match(targetZone, relativeTo(target, targetZone.fqdn())).orElse(null);
    if (rows == null || firstOfType(rows, DnsRecordType.CNAME) != null) {
      return ResolutionResult.answer(answers);
    }
    for (StoredRecord row : rows) {
      // Both address families, whatever the qtype was. The alternative — filtering to the asked
      // type — saves one record on a response that is already three, and costs a dual-stack client
      // the second question. The owner is the TARGET's name, not the queried one; this half of the
      // answer belongs to a different name and says so.
      answers.add(RecordData.of(target, WireType.of(row.type()), row.ttl(), row.value()));
    }
    return ResolutionResult.answer(answers);
  }

  /**
   * Nothing matched. Which negative it is decides how long a resolver remembers it, and for which
   * names.
   */
  private ResolutionResult negative(ZoneData zone, String relative) {
    RecordData soa = soaRecord(zone);
    if (relative.isEmpty()) {
      // The apex always exists — it is the zone. A zone with no `@` rows answers NODATA for them,
      // never NXDOMAIN, which would deny the existence of the very name we are authoritative for.
      return ResolutionResult.noData(soa);
    }
    if (isEmptyNonTerminal(zone, relative)) {
      return ResolutionResult.noData(soa);
    }
    return ResolutionResult.nxDomain(soa);
  }

  /**
   * The one RFC rule kept against the deviation above: a name with no records of its own but with
   * configured names BENEATH it exists as an empty non-terminal, and denying it is NXDOMAIN for its
   * whole subtree. Query {@code x} with only {@code y.x} configured must be NODATA, because a
   * resolver that caches NXDOMAIN for {@code x} then answers {@code y.x} out of that cache without
   * ever asking us again — the record is configured, works once, and stops.
   *
   * <p>Only names one label above the apex can have children here, so this question does not arise
   * deeper. {@code *.*} counts: it configures a name under EVERY one-label name, which makes every
   * one-label name a non-terminal exactly as {@code y.x} makes {@code x} one.
   */
  private static boolean isEmptyNonTerminal(ZoneData zone, String relative) {
    if (relative.indexOf('.') >= 0) {
      return false;
    }
    String suffix = "." + relative;
    for (Map.Entry<String, List<StoredRecord>> entry : zone.byName().entrySet()) {
      if (entry.getValue().isEmpty()) {
        continue;
      }
      String stored = entry.getKey();
      if (stored.endsWith(suffix) || stored.equals("*.*")) {
        return true;
      }
    }
    return false;
  }

  /** The first name in the table with any rows, or empty when the table runs out. */
  private static Optional<List<StoredRecord>> match(ZoneData zone, String relative) {
    for (String candidate : candidates(relative)) {
      List<StoredRecord> rows = zone.byName().get(candidate);
      if (rows != null && !rows.isEmpty()) {
        return Optional.of(rows);
      }
    }
    return Optional.empty();
  }

  /** The names to try for a query this far above the apex, most specific first. */
  private static List<String> candidates(String relative) {
    if (relative.isEmpty()) {
      return List.of(DnsNames.APEX);
    }
    int dot = relative.indexOf('.');
    if (dot < 0) {
      // DELIBERATE DEVIATION FROM RFC 4592, fixed by §3 and not a bug to fix. Real DNS blocks the
      // wildcard here whenever `x` exists as an empty non-terminal — if `y.x` is configured, a
      // query for `x` would be NODATA even with `*` present. We consult `*` anyway: a user who
      // inserted a `*` row meant "cover every one-label name", and a name silently dropping out of
      // that cover because something else was configured beneath it is a rule nobody asked for and
      // nobody would predict. The RFC's version protects a delegation structure this server does
      // not have.
      return List.of(relative, DnsNames.WILDCARD);
    }
    if (relative.indexOf('.', dot + 1) < 0) {
      return List.of(relative, "*." + relative.substring(dot + 1), "*.*");
    }
    // Three or more labels above the apex: the shape grammar cannot express such a name, so no row
    // and no wildcard can ever match one. NXDOMAIN, not NODATA — the name genuinely does not exist.
    return List.of();
  }

  private static StoredRecord firstOfType(List<StoredRecord> rows, DnsRecordType type) {
    for (StoredRecord row : rows) {
      if (row.type() == type) {
        return row;
      }
    }
    return null;
  }

  /**
   * The zone's SOA as an authority-section record, or null when synthesis is off.
   *
   * <p>Null rather than an empty {@code Optional} because that is what {@link
   * ResolutionResult#noData} and {@link ResolutionResult#nxDomain} take: a negative answer is still
   * given when nobody configured a hostmaster address, it just carries an empty authority section.
   */
  private static RecordData soaRecord(ZoneData zone) {
    Optional<SoaData> soa = zone.soa();
    return soa.map(data -> RecordData.soa(zone.fqdn(), zone.defaultTtl(), data)).orElse(null);
  }

  /**
   * Lowercase, and one trailing dot removed.
   *
   * <p>{@link Locale#ROOT} is not decoration: under a Turkish default locale {@code
   * "QITS".toLowerCase()} is {@code "qıts"}, which matches no zone, and the failure would depend on
   * the host's locale rather than on anything in the query.
   *
   * <p>Exactly one dot is stripped. {@code a.b..} is a malformed name, not a name with two trailing
   * dots, and it goes on to match no zone and be REFUSED.
   */
  private static String normalise(String qname) {
    if (qname == null) {
      return "";
    }
    String name = qname.trim().toLowerCase(Locale.ROOT);
    return name.endsWith(".") ? name.substring(0, name.length() - 1) : name;
  }

  /** The labels of {@code qname} above the zone apex, or {@code ""} at the apex itself. */
  private static String relativeTo(String qname, String zoneFqdn) {
    return qname.equals(zoneFqdn) ? "" : qname.substring(0, qname.length() - zoneFqdn.length() - 1);
  }
}
