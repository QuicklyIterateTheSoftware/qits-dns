package eu.wohlben.qits.dns.control;

import eu.wohlben.qits.dns.entity.DnsRecord;
import eu.wohlben.qits.dns.entity.DnsZone;
import eu.wohlben.qits.dns.persistence.DnsRecordRepository;
import eu.wohlben.qits.dns.persistence.DnsZoneRepository;
import eu.wohlben.qits.dns.resolve.SoaData;
import eu.wohlben.qits.dns.resolve.StoredRecord;
import eu.wohlben.qits.dns.resolve.ZoneData;
import eu.wohlben.qits.dns.resolve.ZoneSnapshot;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Turns the two tables into the immutable read model the resolver runs against: the only code in
 * this repo that touches both the database and the hot path's types, and the only place a record's
 * TTL default or a zone's synthesized SOA is decided.
 *
 * <p>It is a build, never an update — {@link #build()} is called at boot and again after every
 * mutating API call, and produces a whole new {@link ZoneSnapshot} each time. At hundreds of rows
 * that costs nothing worth optimising, and it buys the property that matters: there is no partial
 * invalidation to get wrong, so a snapshot can never hold a record the database no longer has.
 *
 * <p><b>The read runs in its own transaction</b>, via {@link QuarkusTransaction#requiringNew()}
 * rather than {@code @Transactional}. That is structural, not stylistic: {@code REQUIRED} would
 * JOIN a caller's in-flight write transaction and publish uncommitted rows, so a write that later
 * rolled back would have already been served to the internet. {@code requiringNew} suspends
 * whatever is running and reads committed state no matter who calls it, which makes "a rebuild
 * always reads committed state" a property of this method instead of a rule its callers have to
 * remember. They still owe it the ordering — a rebuild belongs AFTER the commit, not merely outside
 * the transaction — but a mistake there now costs a stale snapshot rather than a phantom one.
 */
@ApplicationScoped
public class ZoneSnapshotBuilder {

  /**
   * The SOA timers that instruct SECONDARIES, and the reason they are constants rather than config
   * keys: this server has none and refuses AXFR, so nothing on the internet acts on these three
   * numbers. They are carried because the record's format requires them, and they are conventional
   * values (RFC 1912 §2.2's ranges) so that a zone dumped by {@code dig} looks unremarkable to
   * whoever reads it. The one SOA field that still does work — {@code minimum}, the negative-cache
   * TTL since RFC 2308 — tracks {@code qits.dns.ttl-seconds} and is therefore NOT here.
   */
  static final int SOA_REFRESH_SECONDS = 86_400;

  static final int SOA_RETRY_SECONDS = 7_200;
  static final int SOA_EXPIRE_SECONDS = 1_209_600;

  @Inject DnsZoneRepository zones;
  @Inject DnsRecordRepository records;

  @ConfigProperty(name = "qits.dns.ttl-seconds")
  int defaultTtl;

  /**
   * This server's public NS hostnames, comma-separated; the first is also the SOA's {@code mname}.
   *
   * <p>{@code Optional<String>} and not {@code String}: SmallRye Config reads an empty value as
   * UNSET, so a plain {@code String} injection of a key this repo ships BLANK fails the whole
   * deployment at boot with "Failed to load config value of type class java.lang.String". The
   * documented default would be the thing that broke the app.
   */
  @ConfigProperty(name = "qits.dns.ns-names")
  Optional<String> nsNamesConfig;

  /** The SOA's {@code rname}, as a hostname. {@code Optional<String>} for the reason above. */
  @ConfigProperty(name = "qits.dns.hostmaster")
  Optional<String> hostmasterConfig;

  /** A fresh snapshot of committed state. Callable as often as anyone likes. */
  public ZoneSnapshot build() {
    return QuarkusTransaction.requiringNew().call(this::read);
  }

  /**
   * Whether SOA and NS synthesis has what it needs.
   *
   * <p>Both keys or neither, deliberately: a zone answering NS out of a configured {@code ns-names}
   * while having no SOA is a half-delegation that looks configured to whoever queries it, and the
   * two keys are set by the same act of deploying this server somewhere real. {@link
   * ZoneSnapshotHolder} logs one line at boot when this is false, which is the only place the
   * requirement is discoverable without reading the config file.
   */
  public boolean synthesisEnabled() {
    return !nsNames().isEmpty() && hostmaster().isPresent();
  }

  /**
   * The NS hostnames, split.
   *
   * <p>The split lives here rather than in config because a comma list is a string as far as
   * SmallRye is concerned and someone has to own the trimming. Blank entries are dropped rather
   * than rejected: {@code ns1.qits.eu,} is a trailing comma, not a nameserver with no name, and a
   * boot failure over one is a worse outcome than ignoring it.
   */
  List<String> nsNames() {
    String raw = nsNamesConfig.orElse("");
    List<String> names = new ArrayList<>();
    for (String part : raw.split(",", -1)) {
      String name = part.trim();
      if (!name.isEmpty()) {
        names.add(name);
      }
    }
    return names;
  }

  Optional<String> hostmaster() {
    return hostmasterConfig.map(String::trim).filter(value -> !value.isEmpty());
  }

  private ZoneSnapshot read() {
    List<String> nsNames = nsNames();
    Optional<String> hostmaster = hostmaster();
    boolean synthesis = !nsNames.isEmpty() && hostmaster.isPresent();

    List<ZoneData> built = new ArrayList<>();
    for (DnsZone zone : zones.listAllOrdered()) {
      Map<String, List<StoredRecord>> byName = new LinkedHashMap<>();
      for (DnsRecord record : records.listByZoneId(zone.id)) {
        // The TTL default is resolved HERE, once, so that nothing downstream of a snapshot ever
        // reads configuration again — and so that changing qits.dns.ttl-seconds moves every record
        // that never overrode it, with no database write.
        int ttl = record.ttl == null ? defaultTtl : record.ttl;
        byName
            .computeIfAbsent(record.name, key -> new ArrayList<>())
            .add(new StoredRecord(record.name, record.type, ttl, record.value));
      }
      Map<String, List<StoredRecord>> frozen = new LinkedHashMap<>();
      byName.forEach((name, rows) -> frozen.put(name, List.copyOf(rows)));

      Optional<SoaData> soa =
          synthesis
              ? Optional.of(
                  new SoaData(
                      nsNames.get(0),
                      hostmaster.get(),
                      zone.serial,
                      SOA_REFRESH_SECONDS,
                      SOA_RETRY_SECONDS,
                      SOA_EXPIRE_SECONDS,
                      defaultTtl))
              : Optional.empty();

      built.add(
          new ZoneData(
              zone.fqdn,
              zone.serial,
              Map.copyOf(frozen),
              soa,
              synthesis ? List.copyOf(nsNames) : List.of(),
              defaultTtl));
    }
    return ZoneSnapshot.of(built);
  }
}
