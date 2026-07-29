package eu.wohlben.qits.dns.control;

import eu.wohlben.qits.dns.persistence.DnsRecordRepository;
import eu.wohlben.qits.dns.persistence.DnsZoneRepository;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;

/**
 * An empty database in front of every test that needs one.
 *
 * <p>The test datasource is in-memory H2 with {@code clean-at-start}, which resets the schema once
 * per JVM and not once per test — so without this, a class asserting "this zone's records" would
 * pass alone and fail beside its neighbours. Truncating in a transaction of its own keeps each test
 * describing the whole state rather than a delta from whatever ran before it.
 *
 * <p>Records first: the foreign key is real and not in the object model, so nothing cascades.
 */
public abstract class DnsPersistenceTestSupport {

  @Inject protected DnsZoneService zones;
  @Inject protected DnsRecordService records;
  @Inject protected DnsZoneRepository zoneRows;
  @Inject protected DnsRecordRepository recordRows;

  @BeforeEach
  void emptyTheDatabase() {
    QuarkusTransaction.requiringNew()
        .run(
            () -> {
              recordRows.deleteAll();
              zoneRows.deleteAll();
            });
  }
}
