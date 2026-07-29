package eu.wohlben.qits.dns.api;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The static-token guard over the record-management API's write verbs — {@code CiTokenFilter}'s
 * pattern, ported, with one deliberate divergence stated below.
 *
 * <p>The header is {@code X-DNS-Token} and the key is {@code qits.dns.token}. Blank — the shipped
 * default — makes the guard a no-op, which is what keeps {@code quarkus:dev} and the whole suite
 * free of ceremony. Reads are never guarded: what this server holds is what it already tells the
 * open internet over UDP, so a token on a GET would protect nothing that is not public by
 * construction.
 *
 * <p><b>The divergence: this guards EVERY write under {@code /dns/api}, not one named resource.</b>
 * qits-ci matches {@code events/} on purpose, so that a future write elsewhere under its prefix has
 * to opt into the guard consciously. The reasoning does not carry over, because of what a write
 * here does: it changes what a public nameserver answers. A new write route added to this service
 * and forgotten by a path predicate would be an unauthenticated way to repoint a hostname — the
 * failure is silent, it is reachable by anyone who can address the service, and it is exactly the
 * mistake a default-deny guard exists to make impossible. So the predicate is the METHOD alone, and
 * the surface it covers is every JAX-RS resource this application has.
 *
 * <p>That is also why there is no path match to read below. {@code UriInfo.getPath()} is relative
 * to {@code quarkus.rest.path} ({@code /dns/api}), so every request that reaches this filter is
 * already under that prefix and there is no prefix left to test for; a {@code startsWith} against
 * the empty remainder would be decoration that reads like protection. The corresponding test
 * addresses the ABSOLUTE path, so a filter that quietly stopped matching shows up as a 201 rather
 * than as a green assertion about a route nobody hit. Quarkus' non-application endpoints ({@code
 * /dns/q/openapi} and the UI) are not JAX-RS resources and never reach here — they are reads, and
 * open by the same rule.
 */
@Provider
public class DnsTokenFilter implements ContainerRequestFilter {

  static final String TOKEN_HEADER = "X-DNS-Token";

  /** Everything that can change what this nameserver answers. GET and HEAD are not here. */
  private static final Set<String> WRITE_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

  /**
   * {@code Optional<String>} and not {@code String}. SmallRye Config reads an empty value as UNSET,
   * so a plain {@code String} injection of a key this repo ships BLANK fails the whole deployment
   * at boot with "Failed to load config value of type class java.lang.String" — the documented
   * default would be the thing that broke the app. This bit the scaffold once already, and
   * {@code qits.ci.token} carries the same note for the same reason.
   */
  @ConfigProperty(name = "qits.dns.token")
  Optional<String> configuredToken;

  @Override
  public void filter(ContainerRequestContext requestContext) {
    String token = configuredToken.map(String::trim).filter(t -> !t.isEmpty()).orElse(null);
    if (token == null) {
      return; // blank => open, the dev and test default
    }
    if (!WRITE_METHODS.contains(requestContext.getMethod())) {
      return;
    }
    if (!token.equals(requestContext.getHeaderString(TOKEN_HEADER))) {
      requestContext.abortWith(
          Response.status(Response.Status.UNAUTHORIZED)
              .entity(Map.of("message", "Missing or invalid " + TOKEN_HEADER))
              .type(MediaType.APPLICATION_JSON)
              .build());
    }
  }
}
