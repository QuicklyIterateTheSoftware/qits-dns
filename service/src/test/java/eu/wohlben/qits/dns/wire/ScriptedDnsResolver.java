package eu.wohlben.qits.dns.wire;

import eu.wohlben.qits.dns.resolve.DnsResolver;
import eu.wohlben.qits.dns.resolve.ResolutionResult;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The wire suite's resolver: canned answers keyed by {@code (qname, qtype)}, and REFUSED for
 * anything unscripted.
 *
 * <p><b>Real sockets, fake answers.</b> These tests are about bytes on a wire — framing,
 * truncation, EDNS0, case echo, what happens to garbage — and every one of them is decided before a
 * zone is consulted. Scripting the resolver makes each test state its own precondition in one line
 * instead of building a snapshot to imply it, and it means this suite has no dependency at all on
 * the §3 matching rules, which are somebody else's module and somebody else's test file.
 *
 * <p>A globally-enabled {@link Alternative}, so it is the {@link DnsResolver} the wire server
 * injects for the whole test run. When the real implementation lands in {@code dns/} this one
 * displaces it here and nowhere else.
 *
 * <p>{@code @ApplicationScoped}, so a test scripts the same instance the server holds. Tests share
 * a Quarkus instance, so each one {@link #reset}s first — leftovers from another test class are
 * exactly the kind of cross-talk that makes a socket suite flaky for reasons that look like the
 * network.
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class ScriptedDnsResolver implements DnsResolver {

  private final Map<Question, ResolutionResult> answers = new ConcurrentHashMap<>();

  /**
   * One scripted question. {@code qname} is lowercase and dot-less, as the contract delivers it.
   */
  private record Question(String qname, int qtype) {}

  /** Answer {@code qname}/{@code qtype} with {@code result} until the next {@link #reset}. */
  public void script(String qname, int qtype, ResolutionResult result) {
    answers.put(new Question(qname, qtype), result);
  }

  public void reset() {
    answers.clear();
  }

  @Override
  public ResolutionResult resolve(String qname, int qtype) {
    // REFUSED rather than an exception for the unscripted case: it is what the real resolver says
    // about a name outside every zone, so a test that forgets to script gets a plausible response
    // instead of a stack trace that looks like a wire bug.
    return answers.getOrDefault(new Question(qname, qtype), ResolutionResult.refused());
  }
}
