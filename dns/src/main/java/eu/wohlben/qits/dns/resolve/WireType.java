package eu.wohlben.qits.dns.resolve;

import eu.wohlben.qits.dns.entity.DnsRecordType;
import java.util.Optional;

/**
 * The record types that reach the wire, with their RFC 1035 numeric codes.
 *
 * <p>This is a SUPERSET of {@link DnsRecordType}: SOA and NS are answered but never stored, because
 * they are synthesized from configuration rather than created over the API. That asymmetry is the
 * whole reason there are two enums instead of one — the storage enum is what the {@code type} check
 * constraint permits, this one is what an answer section can contain, and conflating them would
 * make an apex NS look like something the API ought to accept.
 *
 * <p>The numeric codes live here, and only here, so the resolver can decide a qtype without the
 * wire layer having translated it first. That is what lets ANY (255) and AXFR (252) / IXFR (251) be
 * REFUSED by the resolver: they have no member in this enum, {@link #fromCode} answers empty for
 * them, and the policy stays in the one place the resolution contract is implemented rather than
 * being split between a codec and a rule set.
 */
public enum WireType {
  A(1),
  AAAA(28),
  CNAME(5),
  SOA(6),
  NS(2);

  private final int code;

  WireType(int code) {
    this.code = code;
  }

  /** The RFC 1035 numeric type code, as it appears in a question and in a resource record. */
  public int code() {
    return code;
  }

  /**
   * The type for a numeric code, or empty when this server does not serve it. Empty is the normal
   * answer for a great many valid codes (MX, TXT, SRV, ANY, AXFR) and is not an error — the caller
   * decides whether that means NODATA or REFUSED, and those two differ.
   */
  public static Optional<WireType> fromCode(int code) {
    for (WireType t : values()) {
      if (t.code == code) {
        return Optional.of(t);
      }
    }
    return Optional.empty();
  }

  /** The wire type a stored record is served as. Total: every storable type is servable. */
  public static WireType of(DnsRecordType type) {
    return switch (type) {
      case A -> A;
      case AAAA -> AAAA;
      case CNAME -> CNAME;
    };
  }
}
