package eu.wohlben.qits.dns.error;

/** 404. */
public class NotFoundException extends DnsException {

  public NotFoundException(String message) {
    super(404, message);
  }
}
