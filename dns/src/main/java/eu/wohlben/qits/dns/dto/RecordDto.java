package eu.wohlben.qits.dns.dto;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import java.time.Instant;

/**
 * One record as returned to clients.
 *
 * <p>{@code name} is the STORED, zone-relative spelling — {@code @}, {@code app.feature}, {@code *}
 * — and never the expanded fqdn a query would see. This surface manages configuration; what a
 * wildcard row expands into is the resolver's business and belongs to a question nobody asked here.
 *
 * <p>{@code ttl} is null when the record carries no override, which is the normal case. It is NOT
 * filled in with {@code qits.dns.ttl-seconds}: a null here means "follows the server default" and
 * substituting the current value would make a subsequent write pin it, silently converting a record
 * that tracks the default into one that does not.
 *
 * <p>The JSON field is {@code value} even though the column is {@code rdata} — H2 2.x reserves the
 * word and the migration had to route around it. That is a storage detail and it stops at the
 * entity.
 */
public record RecordDto(
    String id,
    String zoneId,
    String name,
    DnsRecordType type,
    String value,
    Integer ttl,
    Instant createdAt,
    Instant updatedAt) {}
