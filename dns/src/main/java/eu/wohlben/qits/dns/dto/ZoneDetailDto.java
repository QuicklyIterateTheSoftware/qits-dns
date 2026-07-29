package eu.wohlben.qits.dns.dto;

import java.time.Instant;
import java.util.List;

/**
 * A zone with its records — the single-zone read, where §6 embeds them.
 *
 * <p>The zone's own fields are repeated here rather than nested under a {@code zone} object, so
 * that the single read is the listing's shape plus one field. A client that walks {@code
 * GET /zones} and then {@code GET /zones/{id}} reads {@code fqdn} from the same place in both,
 * which a nested object would make untrue for no gain beyond saving five component declarations.
 */
public record ZoneDetailDto(
    String id,
    String fqdn,
    long serial,
    Instant createdAt,
    Instant updatedAt,
    List<RecordDto> records) {}
