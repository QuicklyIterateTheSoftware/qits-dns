package eu.wohlben.qits.dns.dto;

/**
 * {@code POST /dns/api/zones} — delegate one more domain to this server.
 *
 * <p><b>No bean-validation annotations, here or on any request record in this package.</b> The
 * rules live in {@code DnsNames} and the API layer calls it; a {@code @Pattern} restating what a
 * legal fqdn is would be a second implementation of the shape grammar, and the two would disagree
 * the first time either changed. §14 fixes this: validation is implemented once, in the control
 * layer, and never duplicated in a DTO.
 */
public record CreateZoneRequest(String fqdn) {}
