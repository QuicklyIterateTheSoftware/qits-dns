package eu.wohlben.qits.dns.dto;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import java.util.List;

/**
 * {@code PUT /dns/api/zones/{id}/records} — this is what {@code (name, type)} holds now.
 *
 * <p>The idempotent verb an automated deployer actually wants: re-running the same deployment sends
 * the same body and gets the same state, with no 409 to dance around and no delete-then-create
 * window in which the name resolves to nothing. Rows of OTHER types at the same name are untouched.
 *
 * <p>{@code values} must be non-empty and distinct — a body describing nothing is a serialisation
 * accident far more often than an intent to empty a name, and emptying one is {@code DELETE} on its
 * records. See {@link CreateZoneRequest} on why nothing here is annotated.
 */
public record ReplaceRecordSetRequest(
    String name, DnsRecordType type, List<String> values, Integer ttl) {}
