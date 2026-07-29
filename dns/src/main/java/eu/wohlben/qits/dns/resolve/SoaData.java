package eu.wohlben.qits.dns.resolve;

/**
 * The fields of a synthesized SOA record. NEVER a database row: {@code mname} is the first entry of
 * {@code qits.dns.ns-names} and {@code rname} is {@code qits.dns.hostmaster}, both deployment facts
 * this repo cannot know, and {@code serial} is the zone row's counter. The record is assembled when
 * the snapshot is built and exists only there.
 *
 * <p>{@code refresh}, {@code retry} and {@code expire} are constants in code rather than config
 * keys, because they are instructions to SECONDARIES about when to re-transfer — and this server
 * has none and refuses AXFR. They are carried because the record's format requires them.
 *
 * <p>{@code minimum} is the one field that still does work: since RFC 2308 it is the negative
 * caching TTL, which is how long a resolver remembers an NXDOMAIN or NODATA from us. It tracks
 * {@code qits.dns.ttl-seconds} so that a deployment lowering the TTL to make names move faster also
 * makes their absence stop being remembered as long.
 *
 * <p>{@code rname} is held as a plain hostname ({@code hostmaster.qits.eu}) rather than in the
 * wire's mailbox encoding; converting the first label's separator is the codec's business.
 */
public record SoaData(
    String mname, String rname, long serial, int refresh, int retry, int expire, int minimum) {}
