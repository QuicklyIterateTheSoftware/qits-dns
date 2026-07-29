package eu.wohlben.qits.dns.resolve;

/**
 * The DNS response codes this server can produce, and no others. An authoritative-only server with
 * no recursion, no transfers and no dynamic update has a short list: it answers, or it says the
 * name does not exist, or it declines.
 *
 * <p>There is no {@code NODATA} member because there is no such rcode on the wire — NODATA is
 * {@link #NOERROR} with an empty answer section and the zone's SOA in authority, and it is a
 * distinct outcome only in the sense that a resolver caches it differently from NXDOMAIN. {@link
 * ResolutionResult#noData} is where that shape is constructed.
 *
 * <p>{@link #REFUSED} is the deliberate answer for everything outside our zones, for ANY, and for
 * AXFR/IXFR: each is a request this server has a policy against rather than an error, and REFUSED
 * is the smallest possible response — which is the point when the socket faces the open internet.
 */
public enum ResponseCode {
  NOERROR,
  FORMERR,
  NOTIMP,
  REFUSED,
  NXDOMAIN
}
