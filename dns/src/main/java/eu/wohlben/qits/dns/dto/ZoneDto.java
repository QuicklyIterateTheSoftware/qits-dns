package eu.wohlben.qits.dns.dto;

import java.time.Instant;

/**
 * A zone as returned to clients.
 *
 * <p>{@code serial} is on the wire deliberately: it is the one field that tells a caller whether
 * the write it just made is the one this server is answering from, and an automated deployer
 * waiting for its own change has nothing else to look at.
 */
public record ZoneDto(String id, String fqdn, long serial, Instant createdAt, Instant updatedAt) {}
