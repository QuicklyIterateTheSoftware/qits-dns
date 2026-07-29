package eu.wohlben.qits.dns.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * One domain delegated to this server — a registered name of at least two labels ({@code
 * qits-dev.eu}), never a bare TLD. Its {@link DnsRecord} rows are keyed by {@link DnsRecord#zoneId},
 * not a JPA relation; the foreign key exists in SQL and stays out of the object model, so the
 * snapshot rebuild reads two flat result sets instead of walking a graph.
 *
 * <p>{@link #fqdn} is stored lowercase and WITHOUT a trailing dot — DNS is case-insensitive and the
 * qname is lowercased before matching, so one canonical spelling in the column is what makes the
 * longest-suffix lookup a plain string comparison.
 *
 * <p>{@link #serial} is the SOA serial the synthesized apex record carries, bumped on every write
 * anywhere in the zone. A plain counter: nothing compares it across servers, because there are no
 * secondaries.
 */
@Entity
@Table(name = "dns_zone")
public class DnsZone extends PanacheEntityBase {

  @Id public String id;

  @Column(nullable = false, unique = true, length = 253)
  public String fqdn;

  @Column(nullable = false)
  public long serial;

  @Column(name = "created_at", nullable = false)
  public Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  public Instant updatedAt;
}
