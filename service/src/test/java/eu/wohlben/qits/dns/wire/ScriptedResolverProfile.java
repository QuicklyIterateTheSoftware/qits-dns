package eu.wohlben.qits.dns.wire;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Set;

/**
 * The profile the wire suite runs under: it selects {@link ScriptedDnsResolver} in place of the
 * real {@code DnsResolverImpl}, and it is the ONLY thing that does.
 *
 * <p><b>Why this exists rather than a {@code @Priority} on the alternative itself.</b> A globally
 * selected alternative replaces the bean for the whole module's test run, so the API suite's
 * write-then-resolve loop — a record written over HTTP and then queried over real UDP, which is the
 * single most valuable test in this repo — would have been resolving against canned answers and
 * proving nothing. That failure has no symptom: the test stays green, because a fake that was told
 * the answer gives it. Naming the substitution here means every test that wants the fake says so,
 * and every test that does not gets the implementation a deployment ships.
 *
 * <p>The cost is one extra Quarkus start for the whole wire suite (a profile is an instance
 * boundary), which buys the property that the boundary is visible in each test class's annotations.
 */
public class ScriptedResolverProfile implements QuarkusTestProfile {

  @Override
  public Set<Class<?>> getEnabledAlternatives() {
    return Set.of(ScriptedDnsResolver.class);
  }
}
