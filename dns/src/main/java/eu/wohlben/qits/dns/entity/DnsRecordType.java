package eu.wohlben.qits.dns.entity;

/**
 * The record types the management API can store. Deliberately three: A and AAAA are how a name
 * points at a deployment, CNAME is how a whole family of names follows one.
 *
 * <p>SOA and NS are absent because they are never rows — a delegated zone needs both, and both are
 * synthesized from configuration at snapshot-build time rather than created over the API. See
 * {@code V1__init.sql}.
 *
 * <p>TXT is the first likely extension: ACME DNS-01 is how {@code *.qits-dev.eu} eventually gets a
 * wildcard certificate, and it wants exactly one TXT record at a well-known name. Adding it is this
 * enum plus the {@code type} check constraint in a new migration, plus value validation — the
 * matching rules do not change, because a TXT record is matched by name like everything else.
 */
public enum DnsRecordType {
  A,
  AAAA,
  CNAME
}
