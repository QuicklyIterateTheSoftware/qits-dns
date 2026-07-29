package eu.wohlben.qits.dns.control;

import eu.wohlben.qits.dns.resolve.ZoneSnapshot;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The one mutable cell in the whole resolution path: a {@code volatile} reference to the current
 * {@link ZoneSnapshot}, read by every query and written by every rebuild.
 *
 * <p>Volatile and nothing else — no lock, no copy-on-write collection, no synchronisation on the
 * read side. The snapshot is immutable once built, so publishing one is a single reference write
 * and observing one is a single reference read; a query either sees the old snapshot whole or the
 * new one whole, and there is no third possibility to reason about. That is what keeps the UDP
 * event loop off both a datasource and a lock.
 *
 * <p>A rebuild that throws leaves the previous snapshot in place. Keeping the last known-good
 * answer set is strictly better than emptying it: an empty snapshot REFUSES every query, which
 * looks to the internet exactly like a delegation to the wrong server.
 */
@ApplicationScoped
public class ZoneSnapshotHolder {

  private static final Logger LOG = Logger.getLogger(ZoneSnapshotHolder.class);

  @Inject ZoneSnapshotBuilder builder;

  private volatile ZoneSnapshot current = ZoneSnapshot.empty();

  /**
   * Builds the first snapshot, and says out loud when this server cannot answer SOA or NS.
   *
   * <p>The log line is required by §8 and it is the only signal a half-configured deployment gets.
   * Silence would be the failure mode it exists to prevent: a zone delegated at a registrar to a
   * server that answers A records happily and its own SOA not at all is a delegation that appears
   * to work right up until a resolver needs to cache a negative or verify the delegation — and the
   * cause is two blank config keys nobody was ever told about. So the line names both keys.
   *
   * <p>A failing first build is allowed to fail the boot. A DNS server that came up with an empty
   * snapshot answers REFUSED to everything, which is indistinguishable from a misconfigured
   * delegation and far harder to diagnose than a process that did not start.
   */
  void onStart(@Observes StartupEvent event) {
    if (!builder.synthesisEnabled()) {
      LOG.warn(
          "SOA/NS synthesis is DISABLED: qits.dns.ns-names and qits.dns.hostmaster are both "
              + "required and at least one is blank. A/AAAA/CNAME queries are answered normally, "
              + "but SOA and NS at a zone apex return NODATA and negative answers carry no SOA — "
              + "set both before delegating a zone to this server.");
    }
    rebuild();
    LOG.infof("DNS snapshot built: %d zone(s)", current.zones().size());
  }

  /** The snapshot every query resolves against. Never null. */
  public ZoneSnapshot current() {
    return current;
  }

  /**
   * Re-reads the database and publishes the result.
   *
   * <p>Called AFTER a mutating transaction commits, never inside one — {@link
   * ZoneSnapshotBuilder#build()} suspends any transaction it finds so that it cannot publish
   * uncommitted rows, but the ordering is still the caller's to get right: a rebuild that runs
   * before the commit publishes the state from before the write and nothing rebuilds afterwards.
   */
  public void rebuild() {
    current = builder.build();
    LOG.debugf("DNS snapshot rebuilt: %d zone(s)", current.zones().size());
  }

  /**
   * Publishes a snapshot directly. Package-private and for the §3 suite, which drives {@link
   * DnsResolverImpl} against hand-built snapshots with no database and no container in sight — the
   * whole reason the resolver takes this holder rather than a datasource.
   */
  void publish(ZoneSnapshot snapshot) {
    this.current = snapshot;
  }
}
