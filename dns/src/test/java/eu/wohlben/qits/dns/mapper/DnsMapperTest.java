package eu.wohlben.qits.dns.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.wohlben.qits.dns.dto.RecordDto;
import eu.wohlben.qits.dns.dto.ZoneDetailDto;
import eu.wohlben.qits.dns.dto.ZoneDto;
import eu.wohlben.qits.dns.entity.DnsRecord;
import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.entity.DnsZone;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Every field of both entities reaches its DTO.
 *
 * <p>MapStruct only WARNS about an unmapped target, so a component added to a DTO — or a field
 * renamed on an entity — leaves a null in a response and a line in a build log nobody reads. This
 * is the assertion that turns that into a failure.
 */
@QuarkusTest
public class DnsMapperTest {

  @Inject DnsMapper mapper;

  @Test
  public void aZoneMapsEveryField() {
    DnsZone zone = zone();

    ZoneDto dto = mapper.toDto(zone);

    assertEquals("zone-1", dto.id());
    assertEquals("qits-dev.eu", dto.fqdn());
    assertEquals(7L, dto.serial());
    assertEquals(Instant.EPOCH, dto.createdAt());
    assertEquals(Instant.EPOCH.plusSeconds(1), dto.updatedAt());
  }

  @Test
  public void aRecordMapsEveryField() {
    RecordDto dto = mapper.toDto(record("feature", DnsRecordType.A, "10.0.0.1", 30));

    assertEquals("record-1", dto.id());
    assertEquals("zone-1", dto.zoneId());
    assertEquals("feature", dto.name());
    assertEquals(DnsRecordType.A, dto.type());
    assertEquals("10.0.0.1", dto.value());
    assertEquals(30, dto.ttl());
    assertEquals(Instant.EPOCH, dto.createdAt());
    assertEquals(Instant.EPOCH.plusSeconds(1), dto.updatedAt());
  }

  @Test
  public void anAbsentTtlStaysAbsentRatherThanBecomingTheServerDefault() {
    // Null means "follows qits.dns.ttl-seconds". Filling it in here would make the next write pin
    // it, silently converting a record that tracks the default into one that does not.
    assertNull(mapper.toDto(record("feature", DnsRecordType.A, "10.0.0.1", null)).ttl());
  }

  @Test
  public void theDetailReadCarriesTheZonesFieldsAndItsRecords() {
    ZoneDetailDto detail =
        mapper.toDetail(
            zone(),
            List.of(
                record("@", DnsRecordType.A, "10.0.0.1", null),
                record("*", DnsRecordType.CNAME, "app.qits.eu", null)));

    assertEquals("qits-dev.eu", detail.fqdn());
    assertEquals(7L, detail.serial());
    assertEquals(List.of("@", "*"), detail.records().stream().map(RecordDto::name).toList());
  }

  private static DnsZone zone() {
    DnsZone zone = new DnsZone();
    zone.id = "zone-1";
    zone.fqdn = "qits-dev.eu";
    zone.serial = 7L;
    zone.createdAt = Instant.EPOCH;
    zone.updatedAt = Instant.EPOCH.plusSeconds(1);
    return zone;
  }

  private static DnsRecord record(String name, DnsRecordType type, String value, Integer ttl) {
    DnsRecord record = new DnsRecord();
    record.id = "record-1";
    record.zoneId = "zone-1";
    record.name = name;
    record.type = type;
    record.value = value;
    record.ttl = ttl;
    record.createdAt = Instant.EPOCH;
    record.updatedAt = Instant.EPOCH.plusSeconds(1);
    return record;
  }
}
