package eu.wohlben.qits.dns.mapper;

import eu.wohlben.qits.dns.dto.RecordDto;
import eu.wohlben.qits.dns.dto.ZoneDetailDto;
import eu.wohlben.qits.dns.dto.ZoneDto;
import eu.wohlben.qits.dns.entity.DnsRecord;
import eu.wohlben.qits.dns.entity.DnsZone;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Entity to DTO, the repo's MapStruct convention ({@code componentModel = "jakarta"} so the
 * generated implementation is an injectable bean).
 *
 * <p>One mapper for both entities rather than one each: they are read together on the single-zone
 * endpoint, and the composition below needs both halves in scope anyway.
 *
 * <p>The detail mapping is hand-written. MapStruct could be told to ignore {@code records} and have
 * the boundary fill it in afterwards, but the records are keyed by {@code zoneId} rather than held
 * in a JPA relation — there is nothing on the entity for a generated mapping to walk, so the
 * explicit composition says what is actually happening instead of dressing it as configuration.
 */
@Mapper(componentModel = "jakarta")
public interface DnsMapper {

  ZoneDto toDto(DnsZone entity);

  RecordDto toDto(DnsRecord entity);

  /** A zone with its records attached, for the single-zone read. */
  default ZoneDetailDto toDetail(DnsZone zone, List<DnsRecord> records) {
    return new ZoneDetailDto(
        zone.id,
        zone.fqdn,
        zone.serial,
        zone.createdAt,
        zone.updatedAt,
        records.stream().map(this::toDto).toList());
  }
}
