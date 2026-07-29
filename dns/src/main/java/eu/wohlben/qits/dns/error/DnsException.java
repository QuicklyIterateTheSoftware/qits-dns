package eu.wohlben.qits.dns.error;

/**
 * Base for dns errors. Carries an HTTP-ish status code so the web layer can map it to a response
 * without this module depending on JAX-RS — the framework-free stance the whole {@code dns} module
 * is built on. The {@code service} module maps these via its exception mapper.
 *
 * <p>These are the MANAGEMENT API's errors and have nothing to do with the wire: a resolution never
 * throws, it answers with a {@link eu.wohlben.qits.dns.resolve.ResponseCode}. A DNS query that
 * cannot be satisfied is a normal outcome, not an exception, and the two error vocabularies are
 * kept apart so nobody is tempted to translate a 404 into an NXDOMAIN.
 */
public class DnsException extends RuntimeException {

  private final int statusCode;

  public DnsException(int statusCode, String message) {
    super(message);
    this.statusCode = statusCode;
  }

  public DnsException(int statusCode, String message, Throwable cause) {
    super(message, cause);
    this.statusCode = statusCode;
  }

  public int statusCode() {
    return statusCode;
  }
}
