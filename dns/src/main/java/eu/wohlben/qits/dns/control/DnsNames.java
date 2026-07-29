package eu.wohlben.qits.dns.control;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import eu.wohlben.qits.dns.error.BadRequestException;
import eu.wohlben.qits.dns.error.ConflictException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Every validation rule the management API enforces, in one place. The API layer CALLS this rather
 * than restating the rules as bean-validation annotations on its request records — a second copy of
 * "what a legal record name is" is a copy that drifts, and the one that drifts is always the one a
 * caller reads. The DTOs therefore carry no constraint annotations at all.
 *
 * <p>Static and framework-free, like {@code CiIdentifiers} in the sibling repo and for the same
 * reason: these are pure predicates over strings, they have no state to inject, and they must be
 * testable without booting anything.
 *
 * <p><b>Names are validated, never rewritten</b> — with exactly one exception, the trailing dot
 * on a CNAME target, which is the one place a caller can legitimately spell the same name two ways
 * ({@code app.qits.eu} and {@code app.qits.eu.} are the same name and the wire form has no dot).
 * Everything else must arrive canonical, and in particular <b>uppercase is rejected rather than
 * lowercased</b>. DNS is case-insensitive, so the caller loses nothing by sending lowercase; what a
 * silent lowercasing would cost is a POST whose response body disagrees with its request in the
 * field the caller uses as an identity, and a stored spelling that no longer matches what any log
 * line says was sent. The resolver lowercases the QNAME because that arrives from the open internet
 * and cannot be told anything; the API's caller is one of our own modules and can.
 *
 * <p>The split between the two exception types is the one {@code error/} documents: {@link
 * BadRequestException} for a payload that is malformed on its own terms (a name of no legal shape,
 * a value that is not an address), {@link ConflictException} for a payload that is perfectly legal
 * but that this zone's current contents cannot accept (a duplicate, a CNAME beside anything, a
 * CNAME at the apex, a zone overlapping one already configured).
 */
public final class DnsNames {

  /** The stored name of the zone apex. */
  public static final String APEX = "@";

  /** The wildcard label, as it appears in a stored name. */
  public static final String WILDCARD = "*";

  /**
   * One LDH label: letters, digits and hyphen, never leading or trailing hyphen, 1–63 characters.
   * Lowercase only — see the class comment on why this rejects rather than normalises. The
   * alternation is what makes a single-character label ({@code a}) legal while {@code a-} is not.
   */
  private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

  /** RFC 1035's limit on a whole name, dots included, in its presentation form. */
  private static final int MAX_NAME_LENGTH = 253;

  private DnsNames() {}

  /**
   * The fields of an already-stored row that the conflict rules read.
   *
   * <p>Deliberately not {@code DnsRecord}: the rules are about a (type, value) pair and nothing
   * else, and taking the entity would put a persistence type in the signature of the module's most
   * heavily unit-tested class for no gain. Each service maps its rows into these at the call site.
   */
  public record ExistingRecord(DnsRecordType type, String value) {}

  // --- zones ------------------------------------------------------------------------------------

  /**
   * A zone's fqdn: lowercase LDH labels, at least two of them, no trailing dot, 253 characters at
   * most.
   *
   * <p><b>At least two labels</b> is the rule that says a zone is a registered domain and never a
   * bare TLD. Nothing stops a nameserver from being authoritative for {@code eu} in principle; what
   * stops it here is that a single-label zone in this database is always a typo, and accepting it
   * would make every query for every name under that TLD our problem instead of REFUSED.
   *
   * @return the fqdn, trimmed of surrounding whitespace and otherwise exactly as given
   * @throws BadRequestException if the fqdn is not a legal zone name
   */
  public static String requireZoneFqdn(String fqdn) {
    if (fqdn == null || fqdn.isBlank()) {
      throw new BadRequestException("A zone fqdn is required");
    }
    String value = fqdn.trim();
    if (value.endsWith(".")) {
      throw new BadRequestException(
          "Zone fqdn '" + value + "' must not carry a trailing dot — zones are stored without one");
    }
    requireLabelledName(value, "Zone fqdn");
    if (countLabels(value) < 2) {
      throw new BadRequestException(
          "Zone fqdn '" + value + "' must have at least two labels — a zone is 'a.b', never a TLD");
    }
    return value;
  }

  /**
   * The zone does not exist yet and does not overlap one that does, in either direction.
   *
   * <p>Both directions matter and the symmetry is the point: {@code qits-dev.eu} and {@code
   * a.qits-dev.eu} cannot both be zones here, whichever arrives second. Two zones where one
   * contains the other means a query has two plausible homes, and while the longest-suffix rule
   * would pick one deterministically, the records the loser holds simply stop being served — a
   * failure that looks like data loss and is really a delegation the API should never have
   * accepted. The comparison is at a LABEL BOUNDARY, so {@code notqits-dev.eu} is unrelated to
   * {@code qits-dev.eu} even though one ends with the other.
   *
   * @throws ConflictException if the zone exists or overlaps an existing one
   */
  public static void requireZoneAvailable(String fqdn, Collection<String> existingFqdns) {
    for (String existing : existingFqdns) {
      if (fqdn.equals(existing)) {
        throw new ConflictException("Zone '" + fqdn + "' already exists");
      }
      if (fqdn.endsWith("." + existing)) {
        throw new ConflictException(
            "Zone '" + fqdn + "' lies inside the configured zone '" + existing + "'");
      }
      if (existing.endsWith("." + fqdn)) {
        throw new ConflictException(
            "Zone '" + fqdn + "' would contain the configured zone '" + existing + "'");
      }
    }
  }

  // --- record names -----------------------------------------------------------------------------

  /**
   * A record name, relative to its zone's apex: exactly one of {@code @}, {@code l}, {@code l.l},
   * {@code *}, {@code *.l}, {@code *.*}, where {@code l} is an LDH label.
   *
   * <p>Six shapes and no seventh. Depth stops at two labels because the naming scheme this server
   * exists for stops there ({@code app.epic.qits-dev.eu}), and the wildcard always occupies the
   * LEFTMOST label because that is the only position a DNS wildcard can occupy — {@code l.*} is
   * rejected for that reason, not for tidiness. {@code *.*} is the two-label counterpart of {@code
   * *}, and it is a legal stored name here while it is not a legal wildcard in RFC 4592; see the
   * matching table in {@code DnsResolverImpl}, which expands it the same way.
   *
   * @return the name, trimmed of surrounding whitespace and otherwise exactly as given
   * @throws BadRequestException if the name is of no legal shape
   */
  public static String requireRecordName(String name) {
    if (name == null || name.isBlank()) {
      throw new BadRequestException("A record name is required");
    }
    String value = name.trim();
    if (!isLegalRecordName(value)) {
      throw new BadRequestException(
          "Record name '"
              + value
              + "' is not one of the six legal shapes: '@', 'label', 'label.label', '*', "
              + "'*.label', '*.*' (lowercase letters, digits and hyphens; no trailing dot)");
    }
    return value;
  }

  private static boolean isLegalRecordName(String name) {
    if (APEX.equals(name)) {
      return true;
    }
    int dot = name.indexOf('.');
    if (dot < 0) {
      return WILDCARD.equals(name) || isLabel(name);
    }
    if (name.indexOf('.', dot + 1) >= 0) {
      return false;
    }
    String left = name.substring(0, dot);
    String right = name.substring(dot + 1);
    boolean leftOk = WILDCARD.equals(left) || isLabel(left);
    // `l.*` is not a shape: a wildcard only ever matches in the leftmost position, so a `*` on the
    // right would be a literal asterisk label that no query can produce.
    boolean rightOk = isLabel(right) || (WILDCARD.equals(right) && WILDCARD.equals(left));
    return leftOk && rightOk;
  }

  // --- values -----------------------------------------------------------------------------------

  /**
   * A record's payload, checked against its type: an IPv4 literal for A, an IPv6 literal for AAAA,
   * an absolute hostname for CNAME.
   *
   * <p><b>The address literals are parsed here, by hand, and never handed to {@code
   * InetAddress}.</b> {@code InetAddress.getByName} does not resolve a literal — but deciding
   * whether a given string IS a literal is exactly the question being asked, so guarding the call
   * with a shape check means writing the parser anyway and then also trusting the JDK not to treat
   * some edge (a bare integer, a trailing zone id) as a hostname. A validator on the write path of
   * an internet-facing service must not be one typo away from a DNS lookup, so there is no lookup
   * to reach.
   *
   * @return the value, normalised only in the one documented way (a CNAME's trailing dot is
   *     stripped)
   * @throws BadRequestException if the value is not well-formed for the type
   */
  public static String requireValue(DnsRecordType type, String value) {
    if (type == null) {
      throw new BadRequestException("A record type is required (A, AAAA or CNAME)");
    }
    if (value == null || value.isBlank()) {
      throw new BadRequestException("A record value is required");
    }
    String trimmed = value.trim();
    return switch (type) {
      case A -> {
        if (!isIpv4Literal(trimmed)) {
          throw new BadRequestException("'" + trimmed + "' is not an IPv4 address literal");
        }
        yield trimmed;
      }
      case AAAA -> {
        if (!isIpv6Literal(trimmed)) {
          throw new BadRequestException("'" + trimmed + "' is not an IPv6 address literal");
        }
        yield trimmed;
      }
      case CNAME -> requireCnameTarget(trimmed);
    };
  }

  /**
   * A CNAME target: an absolute hostname of at least two lowercase LDH labels, stored WITHOUT the
   * trailing dot.
   *
   * <p>The dot is accepted and stripped because a fully-qualified name is conventionally written
   * with one and a caller copying a target out of a zone file will bring it along; the stored form
   * has no dot because that is what the resolver compares against zone fqdns when it decides
   * whether the target is one of ours and worth chasing.
   */
  private static String requireCnameTarget(String value) {
    String target = value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
    if (target.isEmpty()) {
      throw new BadRequestException("A CNAME target is required");
    }
    requireLabelledName(target, "CNAME target");
    if (countLabels(target) < 2) {
      throw new BadRequestException(
          "CNAME target '" + target + "' must be an absolute hostname of at least two labels");
    }
    return target;
  }

  /**
   * A per-record TTL override, when one was given.
   *
   * <p>Zero is legal and means "do not cache" — occasionally what you want while a deployment is
   * moving. The upper bound is RFC 2181's: the field is a 31-bit unsigned on the wire, so anything
   * an {@code Integer} can hold that is not negative already fits.
   *
   * @throws BadRequestException if the TTL is negative
   */
  public static Integer requireTtl(Integer ttl) {
    if (ttl != null && ttl < 0) {
      throw new BadRequestException("A record TTL must not be negative");
    }
    return ttl;
  }

  // --- the state-dependent rules ----------------------------------------------------------------

  /**
   * The rules for adding one row at a name, given every row already there.
   *
   * @throws ConflictException if the row cannot join the ones already at this name
   */
  public static void requireAddable(
      String name, DnsRecordType type, String value, Collection<ExistingRecord> existingAtName) {
    requireNotApexCname(name, type);
    if (type == DnsRecordType.CNAME && !existingAtName.isEmpty()) {
      throw new ConflictException(cnameExclusivity(name));
    }
    for (ExistingRecord existing : existingAtName) {
      if (existing.type() == DnsRecordType.CNAME) {
        throw new ConflictException(cnameExclusivity(name));
      }
      if (existing.type() == type && existing.value().equals(value)) {
        throw new ConflictException(
            "A " + type + " record for '" + name + "' with value '" + value + "' already exists");
      }
    }
  }

  /**
   * The rules for replacing the whole set of rows at one {@code (name, type)}, given every row
   * currently at that name. Rows of the SAME type are the ones being replaced and so can never
   * conflict; only the other types constrain the swap.
   *
   * <p>An empty {@code values} list is rejected rather than treated as "delete the set". The verb
   * is a replace and its body describes what the name should hold afterwards; a body that describes
   * nothing reads as a serialisation accident far more often than as an intent, and deleting rows
   * on the strength of an accident is not a mistake this API should be able to make. Removing a set
   * is {@code DELETE} on each of its records.
   *
   * @throws BadRequestException if the value list is empty or repeats a value
   * @throws ConflictException if the resulting set could not coexist with the rows of other types
   */
  public static void requireReplaceable(
      String name,
      DnsRecordType type,
      List<String> values,
      Collection<ExistingRecord> existingAtName) {
    requireNotApexCname(name, type);
    if (values == null || values.isEmpty()) {
      throw new BadRequestException(
          "A replace needs at least one value; delete the records instead to empty a name");
    }
    Set<String> distinct = new HashSet<>(values);
    if (distinct.size() != values.size()) {
      throw new BadRequestException("The values of a replace must be distinct");
    }
    List<ExistingRecord> otherTypes = new ArrayList<>();
    for (ExistingRecord existing : existingAtName) {
      if (existing.type() != type) {
        otherTypes.add(existing);
      }
    }
    if (type == DnsRecordType.CNAME) {
      if (values.size() > 1) {
        // Not merely "unsupported": a name with two CNAMEs has two canonical names, and a resolver
        // handed both has no defined behaviour. Same rule as the one below, seen from the payload.
        throw new ConflictException(
            "A name may hold exactly one CNAME; '" + name + "' was given " + values.size());
      }
      if (!otherTypes.isEmpty()) {
        throw new ConflictException(cnameExclusivity(name));
      }
      return;
    }
    for (ExistingRecord existing : otherTypes) {
      if (existing.type() == DnsRecordType.CNAME) {
        throw new ConflictException(cnameExclusivity(name));
      }
    }
  }

  /**
   * RFC 1034 §3.6.2: a CNAME may not share an owner name with any other record, and the apex
   * necessarily carries SOA and NS — so the apex can never hold one, no matter what else is or is
   * not there.
   *
   * <p>The message names the workaround because the caller who hits this wanted something
   * reasonable ("the apex should follow the same target as the wildcards") and the fix is one they
   * can apply immediately. ALIAS-style apex flattening — resolving the target at snapshot-build
   * time and serving its addresses — is the real answer and belongs to whichever plan brings the
   * callers that need it.
   */
  private static void requireNotApexCname(String name, DnsRecordType type) {
    if (APEX.equals(name) && type == DnsRecordType.CNAME) {
      throw new ConflictException(
          "A CNAME cannot sit at the zone apex '@' (RFC 1034 §3.6.2: the apex carries SOA and NS, "
              + "and a CNAME may not share a name with any other record). Give the apex A/AAAA "
              + "records pointing at the same addresses the CNAME target resolves to.");
    }
  }

  private static String cnameExclusivity(String name) {
    return "'"
        + name
        + "' cannot hold a CNAME beside records of any other type (RFC 1034 §3.6.2); a CNAME is "
        + "the whole answer for the name it owns";
  }

  // --- the primitives ---------------------------------------------------------------------------

  private static void requireLabelledName(String value, String what) {
    if (value.length() > MAX_NAME_LENGTH) {
      throw new BadRequestException(
          what + " must be at most " + MAX_NAME_LENGTH + " characters, not " + value.length());
    }
    for (String label : value.split("\\.", -1)) {
      if (!isLabel(label)) {
        throw new BadRequestException(
            what
                + " '"
                + value
                + "' has an illegal label '"
                + label
                + "': labels are 1–63 lowercase letters, digits and hyphens, and may not start or "
                + "end with a hyphen");
      }
    }
  }

  private static boolean isLabel(String label) {
    return label.length() <= 63 && LABEL.matcher(label).matches();
  }

  private static int countLabels(String value) {
    return value.split("\\.", -1).length;
  }

  /**
   * Dotted quad, four decimal octets.
   *
   * <p>A leading zero is rejected ({@code 010.1.1.1}) rather than read as decimal: the C library
   * reads it as OCTAL, so a string that means one host to this validator and another to whatever
   * consumes the answer is precisely the kind of disagreement an address validator exists to
   * prevent. So are the short forms {@code 10.1} and {@code 0x0a000001}, which {@code inet_aton}
   * accepts and which are not dotted quads.
   */
  static boolean isIpv4Literal(String value) {
    String[] parts = value.split("\\.", -1);
    if (parts.length != 4) {
      return false;
    }
    for (String part : parts) {
      if (part.isEmpty() || part.length() > 3) {
        return false;
      }
      if (part.length() > 1 && part.charAt(0) == '0') {
        return false;
      }
      int octet = 0;
      for (int i = 0; i < part.length(); i++) {
        char c = part.charAt(i);
        if (c < '0' || c > '9') {
          return false;
        }
        octet = octet * 10 + (c - '0');
      }
      if (octet > 255) {
        return false;
      }
    }
    return true;
  }

  /**
   * RFC 4291 text form: up to eight groups of 1–4 hex digits, at most one {@code ::} standing for
   * one or more all-zero groups, optionally ending in an embedded IPv4 literal that counts as two
   * groups.
   *
   * <p>A scope/zone id ({@code fe80::1%eth0}) is rejected outright. It is meaningful only on the
   * host that wrote it, and this value is going onto the wire for the entire internet to read.
   */
  static boolean isIpv6Literal(String value) {
    if (value.isEmpty() || value.indexOf('%') >= 0) {
      return false;
    }
    String head = value;
    String tail = null;
    int compression = value.indexOf("::");
    if (compression >= 0) {
      if (value.indexOf("::", compression + 1) >= 0) {
        return false;
      }
      head = value.substring(0, compression);
      tail = value.substring(compression + 2);
    }
    List<String> headGroups = splitGroups(head);
    List<String> tailGroups = splitGroups(tail);
    if (headGroups == null || tailGroups == null) {
      return false;
    }
    List<String> last = tailGroups.isEmpty() ? headGroups : tailGroups;
    int groups = headGroups.size() + tailGroups.size();
    if (!last.isEmpty() && last.get(last.size() - 1).indexOf('.') >= 0) {
      if (!isIpv4Literal(last.get(last.size() - 1))) {
        return false;
      }
      groups += 1; // the embedded quad occupies two groups, and was counted as one
      last.remove(last.size() - 1);
    }
    for (String group : headGroups) {
      if (!isHexGroup(group)) {
        return false;
      }
    }
    for (String group : tailGroups) {
      if (!isHexGroup(group)) {
        return false;
      }
    }
    // With no `::` every group must be written out; with one, it must stand for at least one group.
    return compression < 0 ? groups == 8 : groups < 8;
  }

  /** The groups of one side of a {@code ::}, or null if that side is malformed. Mutable. */
  private static List<String> splitGroups(String side) {
    List<String> groups = new ArrayList<>();
    if (side == null || side.isEmpty()) {
      return groups;
    }
    for (String group : side.split(":", -1)) {
      if (group.isEmpty()) {
        return null; // a stray colon: ':::', a leading or trailing single ':'
      }
      groups.add(group);
    }
    return groups;
  }

  private static boolean isHexGroup(String group) {
    if (group.isEmpty() || group.length() > 4) {
      return false;
    }
    for (int i = 0; i < group.length(); i++) {
      char c = group.charAt(i);
      boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
      if (!hex) {
        return false;
      }
    }
    return true;
  }
}
