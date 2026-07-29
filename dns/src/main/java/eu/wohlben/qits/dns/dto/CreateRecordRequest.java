package eu.wohlben.qits.dns.dto;

import eu.wohlben.qits.dns.entity.DnsRecordType;

/**
 * {@code POST /dns/api/zones/{id}/records} — add one record.
 *
 * <p>{@code name} is zone-relative and one of the six configured shapes; {@code ttl} is null to
 * follow {@code qits.dns.ttl-seconds}, which is what almost every caller wants. See {@link
 * CreateZoneRequest} on why nothing here is annotated.
 */
public record CreateRecordRequest(String name, DnsRecordType type, String value, Integer ttl) {}
