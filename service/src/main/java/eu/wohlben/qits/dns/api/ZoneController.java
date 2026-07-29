package eu.wohlben.qits.dns.api;

import eu.wohlben.qits.dns.control.DnsZoneService;
import eu.wohlben.qits.dns.control.ZoneSnapshotHolder;
import eu.wohlben.qits.dns.dto.CreateZoneRequest;
import eu.wohlben.qits.dns.dto.ZoneDetailDto;
import eu.wohlben.qits.dns.dto.ZoneDto;
import eu.wohlben.qits.dns.entity.DnsZone;
import eu.wohlben.qits.dns.mapper.DnsMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;

/**
 * Zone lifecycle over HTTP: §6's four zone rows, and nothing else.
 *
 * <p><b>This class holds no rules.</b> It unwraps a request record, calls {@link DnsZoneService},
 * maps the result and picks a status code — every decision about what a zone fqdn may be, and about
 * which zones may coexist, is {@code DnsNames}' and reaches the client as a {@link
 * eu.wohlben.qits.dns.error.DnsException} that {@link DnsExceptionMapper} turns into a status. That
 * is why there is no {@code @Valid} and no constraint annotation anywhere on this surface: a
 * {@code @Pattern} restating the fqdn grammar would be a second copy of it, and the interesting
 * rules here (a zone may not be a suffix of another zone) cannot be written as an annotation at
 * all.
 *
 * <p><b>The rebuild after a mutation lives here, and it is load-bearing.</b> The control services
 * are {@code @Transactional} and deliberately do not rebuild — they cannot see their own commit, so
 * a rebuild from inside would publish rows that may still roll back. This method is NOT
 * transactional, so the service call's transaction has committed by the time it returns and the
 * next line runs against durable state. It also gives the ordering for free on the failure side: a
 * service call that throws never reaches the rebuild, so a rejected write cannot publish anything —
 * see {@code DnsSnapshotRebuildTest}, which asserts the snapshot reference is not even replaced.
 *
 * <p>The alternative considered and not taken was a {@code TransactionSynchronizationRegistry}
 * afterCompletion hook registered inside the service. It is the right tool when the commit is
 * somebody else's — but here the boundary owns the transaction outright, and a hook would move the
 * ordering into a callback that no reader of the write path passes through.
 *
 * <p>The {@code @Path} values are relative to {@code quarkus.rest.path}, which is {@code /dns/api}.
 * They must never repeat the {@code dns} segment; the routes are {@code /dns/api/zones...} because
 * of the config, not because of anything written here.
 *
 * <p><b>The class is rooted at {@code /} and each method spells its whole path, which looks like an
 * oversight and is not.</b> Quarkus REST picks ONE resource class by its class-level template and
 * then matches methods only within it — there is no backtracking to a second candidate. With this
 * class at {@code @Path("/zones")}, {@link RecordController}'s {@code /zones/{id}/records} was
 * claimed by this class, matched no method here, and answered <b>404</b> — a routing failure that
 * looks exactly like a missing resource and is reported by neither class. Both controllers
 * therefore share the root template, which puts every method of both into one match set. Any new
 * resource under {@code /dns/api} whose paths interleave with these has to do the same.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ZoneController {

  @Inject DnsZoneService zones;

  @Inject DnsMapper mapper;

  @Inject ZoneSnapshotHolder snapshots;

  /** The zone listing, wrapped so the document can grow a field beside it later. */
  public record ListZonesResponse(List<ZoneDto> zones) {}

  /**
   * Delegates one more domain to this server.
   *
   * <p>201 with a {@code Location} built from {@link UriInfo} rather than assembled from a string:
   * the prefix is {@code quarkus.rest.path}'s and a literal {@code "/dns/api/zones/"} here would be
   * a third place that segment is spelled, one of which would eventually be wrong.
   */
  @POST
  @Path("zones")
  public Response create(CreateZoneRequest request, @Context UriInfo uriInfo) {
    // A body that is absent entirely deserializes to null, and a null fqdn is the same mistake as a
    // blank one — so it goes to the validator rather than through an NPE and a 500.
    DnsZone zone = zones.create(request == null ? null : request.fqdn());
    snapshots.rebuild();
    return Response.created(uriInfo.getAbsolutePathBuilder().path(zone.id).build())
        .entity(mapper.toDto(zone))
        .build();
  }

  /** Every zone, ordered by fqdn, without records. */
  @GET
  @Path("zones")
  public ListZonesResponse list() {
    return new ListZonesResponse(zones.list().stream().map(mapper::toDto).toList());
  }

  /**
   * One zone with its records embedded — the read §6 puts them on, and the only one.
   *
   * <p>Two service calls rather than one, because the records are keyed by {@code zoneId} and are
   * not a JPA relation on the zone. {@code recordsOf} re-checks the id, so a zone deleted between
   * the two calls is a 404 rather than a zone with a suspiciously empty record list.
   */
  @GET
  @Path("zones/{zoneId}")
  public ZoneDetailDto get(@PathParam("zoneId") String zoneId) {
    DnsZone zone = zones.get(zoneId);
    return mapper.toDetail(zone, zones.recordsOf(zoneId));
  }

  /** Deletes a zone and every record under it. 204: there is nothing left to describe. */
  @DELETE
  @Path("zones/{zoneId}")
  public Response delete(@PathParam("zoneId") String zoneId) {
    zones.delete(zoneId);
    snapshots.rebuild();
    return Response.noContent().build();
  }
}
