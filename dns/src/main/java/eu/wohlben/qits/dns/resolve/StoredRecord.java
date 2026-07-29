package eu.wohlben.qits.dns.resolve;

import eu.wohlben.qits.dns.entity.DnsRecordType;

/**
 * One configured record as STORED — the snapshot's copy of a {@link
 * eu.wohlben.qits.dns.entity.DnsRecord} row, with the entity and its persistence context left
 * behind.
 *
 * <p>{@code name} is relative to the zone apex and is one of the six configured shapes: {@code @},
 * {@code l}, {@code l.l}, {@code *}, {@code *.l}, {@code *.*}. It is the STORED spelling, wildcards
 * included, which is what distinguishes this type from {@link RecordData}: matching happens against
 * these, answering happens with those, and the expansion of a {@code *} into the queried owner name
 * is exactly the step between them.
 *
 * <p>{@code ttl} is already defaulted to the zone's, so it is a primitive here where the column is
 * nullable. Nothing downstream of a snapshot build reads configuration.
 */
public record StoredRecord(String name, DnsRecordType type, int ttl, String value) {}
