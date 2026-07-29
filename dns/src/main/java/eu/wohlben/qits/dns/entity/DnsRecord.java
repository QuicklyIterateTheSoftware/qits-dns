package eu.wohlben.qits.dns.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One configured record under a {@link DnsZone}. {@link #zoneId} is a plain string rather than a
 * {@code @ManyToOne} — the same shape qits-ci gives {@code CiStep.runId}, for the same reason: the
 * only thing that reads these in bulk is the snapshot rebuild, which wants every record of every
 * zone as one flat list and would pay for a graph it immediately flattens. The SQL foreign key is
 * still there; it just does not surface here.
 *
 * <p>{@link #name} is stored RELATIVE to the zone apex and is exactly one of six shapes: {@code @},
 * {@code <label>}, {@code <label>.<label>}, {@code *}, {@code *.<label>}, {@code *.*}. The wildcard
 * shapes are ordinary values in this column — a wildcard is opted into by inserting its row, and
 * there is no flag anywhere that turns wildcards on for a zone.
 *
 * <p>{@link #ttl} is nullable and null means "use {@code qits.dns.ttl-seconds}". The default is
 * resolved when the snapshot is built, so a deployment that changes the config TTL changes every
 * record that never overrode it, without a database write.
 */
@Entity
@Table(name = "dns_record")
public class DnsRecord extends PanacheEntityBase {

  @Id public String id;

  @Column(name = "zone_id", nullable = false)
  public String zoneId;

  @Column(nullable = false)
  public String name;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 8)
  public DnsRecordType type;

  /**
   * The record's payload — an IPv4 literal for A, an IPv6 literal for AAAA, a hostname for CNAME.
   * The COLUMN is {@code rdata} (RFC 1035's name for the field) because {@code VALUE} is a reserved
   * word in H2 2.x and an unquoted column of that name fails the migration; the field keeps the name
   * everything else in the repo uses. See {@code V1__init.sql}.
   */
  @Column(name = "rdata", nullable = false, length = 253)
  public String value;

  @Column public Integer ttl;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
