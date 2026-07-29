package eu.wohlben.qits.dns.error;

/**
 * 409 — the request is well-formed but the thing it addresses is in the wrong state for it. Most of
 * this module's rules land here rather than at 400: a CNAME beside another record, a CNAME at the
 * apex, a duplicate (zone, name, type, value), a zone that is a suffix of one already configured.
 * Each is a perfectly legal payload that this database cannot hold.
 */
public class ConflictException extends DnsException {

  public ConflictException(String message) {
    super(409, message);
  }
}
