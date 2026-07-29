package eu.wohlben.qits.dns.api;

import eu.wohlben.qits.dns.error.DnsException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;

/**
 * Turns the {@code dns} module's framework-free {@link DnsException}s into HTTP responses —
 * {@code CiExceptionMapper}, ported, and living in {@code service} for the same reason: the domain
 * module carries no JAX-RS, so the status code rides on the exception as a plain int and the
 * translation happens exactly here.
 *
 * <p>The body is {@code {"message": ...}} and the message is the validator's own sentence, not a
 * reason phrase. That matters more here than in the sibling: this module's rules are the kind a
 * caller gets wrong for reasons a status code cannot express — a CNAME at the apex is a 409 whose
 * message names the A/AAAA workaround, a zone rejected for overlapping another names the zone it
 * overlaps. Replacing those with "Conflict" would throw away the only part of the response that
 * tells an automated deployer's operator what to do next.
 */
@Provider
public class DnsExceptionMapper implements ExceptionMapper<DnsException> {

  @Override
  public Response toResponse(DnsException exception) {
    int status = exception.statusCode();
    String message = exception.getMessage();
    if (message == null || message.isBlank()) {
      message = Response.Status.fromStatusCode(status).getReasonPhrase();
    }
    return Response.status(status)
        .entity(Map.of("message", message))
        .type(MediaType.APPLICATION_JSON)
        .build();
  }
}
