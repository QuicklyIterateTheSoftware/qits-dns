package eu.wohlben.qits.dns.error;

/** 400 — the payload itself is wrong (a malformed fqdn, a record name of no legal shape). */
public class BadRequestException extends DnsException {

  public BadRequestException(String message) {
    super(400, message);
  }
}
