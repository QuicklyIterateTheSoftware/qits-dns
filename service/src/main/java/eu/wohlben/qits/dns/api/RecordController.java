package eu.wohlben.qits.dns.api;

import eu.wohlben.qits.dns.control.DnsRecordService;
import eu.wohlben.qits.dns.control.ZoneSnapshotHolder;
import eu.wohlben.qits.dns.dto.CreateRecordRequest;
import eu.wohlben.qits.dns.dto.RecordDto;
import eu.wohlben.qits.dns.dto.ReplaceRecordSetRequest;
import eu.wohlben.qits.dns.entity.DnsRecord;
import eu.wohlben.qits.dns.mapper.DnsMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.util.List;

/**
 * The record rows of §6: create one under a zone, replace a whole {@code (name, type)} set, delete
 * one by its own id.
 *
 * <p><b>The class is rooted at {@code /} rather than at a prefix, and that is the asymmetry §6
 * fixes.</b> Two of these routes hang under {@code /zones/{zoneId}/records} — creating a record
 * needs the zone as scope, because a record name is meaningless without the apex it is relative to
 * — while the third is {@code DELETE /records/{recordId}}. A record id is already globally unique
 * and already names exactly one row in exactly one zone, so requiring the caller to carry the zone
 * id alongside it would ask for a fact the server can look up and would invite a mismatched pair
 * that has to be diagnosed and rejected. The two shapes are therefore not siblings and the class
 * cannot have one prefix; it has none, and each method spells its own whole path.
 *
 * <p>{@link ZoneController} is rooted at {@code /} for the same reason and it is not free to move:
 * Quarkus REST selects one resource class by its class-level template and never falls back to
 * another, so a {@code @Path("/zones")} there would swallow {@code /zones/{id}/records} and answer
 * 404 without either class being asked. That failure is silent in both directions — see the note
 * there.
 *
 * <p>Like {@link ZoneController} this holds no rules — {@code DnsNames} owns every one of them, via
 * {@link DnsRecordService} — and like it, the snapshot rebuild sits after the {@code
 * @Transactional} call has returned and therefore after its commit. See {@link ZoneController}'s
 * class comment for why that ordering is the whole point and why it is written here rather than in
 * the service.
 */
@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RecordController {

  @Inject DnsRecordService records;

  @Inject DnsMapper mapper;

  @Inject ZoneSnapshotHolder snapshots;

  /** What a {@code (name, type)} holds after a replace, in the order the body gave. */
  public record RecordSetResponse(List<RecordDto> records) {}

  /**
   * Adds one record to a zone. 201 with a {@code Location} pointing at {@code /records/{id}} — the
   * address the record is deletable at, which is not a sub-path of the one it was created under.
   */
  @POST
  @Path("zones/{zoneId}/records")
  public Response create(
      @PathParam("zoneId") String zoneId, CreateRecordRequest request, @Context UriInfo uriInfo) {
    CreateRecordRequest body =
        request == null ? new CreateRecordRequest(null, null, null, null) : request;
    DnsRecord record =
        records.create(zoneId, body.name(), body.type(), body.value(), body.ttl());
    snapshots.rebuild();
    return Response.created(
            uriInfo.getBaseUriBuilder().path("records").path(record.id).build())
        .entity(mapper.toDto(record))
        .build();
  }

  /**
   * Replace-by-{@code (name, type)}: this is what that pair holds now.
   *
   * <p>200 and not 201, even when the name held nothing before. The verb describes a state rather
   * than a creation, an automated deployer runs it repeatedly against the same name, and a status
   * that flipped between 200 and 201 depending on what was already there would make "did my deploy
   * change anything" a question the status code answers wrongly — it changed nothing on a re-run,
   * and the resource it addresses is the set, which existed as a concept either way.
   */
  @PUT
  @Path("zones/{zoneId}/records")
  public RecordSetResponse replace(
      @PathParam("zoneId") String zoneId, ReplaceRecordSetRequest request) {
    ReplaceRecordSetRequest body =
        request == null ? new ReplaceRecordSetRequest(null, null, null, null) : request;
    List<DnsRecord> written =
        records.replaceSet(zoneId, body.name(), body.type(), body.values(), body.ttl());
    snapshots.rebuild();
    return new RecordSetResponse(written.stream().map(mapper::toDto).toList());
  }

  /** Deletes one record. 204. */
  @DELETE
  @Path("records/{recordId}")
  public Response delete(@PathParam("recordId") String recordId) {
    records.delete(recordId);
    snapshots.rebuild();
    return Response.noContent().build();
  }
}
