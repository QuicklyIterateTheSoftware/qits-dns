package eu.wohlben.qits.dns.wire;

/**
 * Thrown when bytes off the socket are not a parseable DNS message.
 *
 * <p>A datagram that raises this is DROPPED, never answered. Answering FORMERR to garbage would
 * hand anyone who can forge a source address a reflector: the attacker spends a few bytes, the
 * victim receives a response, and we pay the bandwidth for both. FORMERR exists for a message we
 * understood well enough to know was malformed — a parseable query with QDCOUNT != 1 — and that
 * case never reaches this exception.
 *
 * <p>Checked rather than unchecked on purpose: dropping is a decision the caller has to make
 * visibly, and an unchecked exception here would be caught by whatever generic handler the event
 * loop has and logged as a failure rather than counted as the expected background noise it is.
 */
public class DnsFormatException extends Exception {

  public DnsFormatException(String message) {
    super(message);
  }

  public DnsFormatException(String message, Throwable cause) {
    super(message, cause);
  }
}
