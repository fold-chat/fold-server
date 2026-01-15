package chat.fray.api;

import chat.fray.auth.FraySecurityContext;
import chat.fray.db.RoleRepository;
import chat.fray.security.Permission;
import chat.fray.service.RoleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;
import java.util.Map;

@Path("/api/v0")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class RoleResource {

    @Inject RoleService roleService;
    @Inject RoleRepository roleRepo;
    @Context ContainerRequestContext requestContext;

    // --- Role CRUD ---

    @GET
    @Path("/roles")
    public Response listRoles() {
        var roles = roleRepo.findAll().stream()
                .map(roleService::serializeRole)
                .toList();
        return Response.ok(roles).build();
    }

    @POST
    @Path("/roles")
    public Response createRole(CreateRoleRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_name", "message", "Name required")).build();
        }
        long permissions = req.permissions() != null ? Permission.fromNames(req.permissions()) : 0L;
        var role = roleService.createRole(sc().getUserId(), req.name().trim(), permissions, req.position(), req.color());
        return Response.status(201).entity(role).build();
    }

    @PATCH
    @Path("/roles/{id}")
    public Response updateRole(@PathParam("id") String id, UpdateRoleRequest req) {
        Long permissions = req.permissions() != null ? Permission.fromNames(req.permissions()) : null;
        var role = roleService.updateRole(sc().getUserId(), id, req.name(), permissions, req.position(), req.color());
        return Response.ok(role).build();
    }

    @DELETE
    @Path("/roles/{id}")
    public Response deleteRole(@PathParam("id") String id) {
        long userCount = roleService.deleteRole(sc().getUserId(), id);
        return Response.ok(Map.of("deleted", true, "affected_users", userCount)).build();
    }

    // --- Member role assignment ---

    @PUT
    @Path("/members/{userId}/roles/{roleId}")
    public Response assignRole(@PathParam("userId") String userId, @PathParam("roleId") String roleId) {
        roleService.assignRole(sc().getUserId(), userId, roleId);
        return Response.noContent().build();
    }

    @DELETE
    @Path("/members/{userId}/roles/{roleId}")
    public Response removeRole(@PathParam("userId") String userId, @PathParam("roleId") String roleId) {
        roleService.removeRole(sc().getUserId(), userId, roleId);
        return Response.noContent().build();
    }

    // --- Channel permission overrides ---

    @GET
    @Path("/channels/{channelId}/permissions")
    public Response listChannelOverrides(@PathParam("channelId") String channelId) {
        var overrides = roleRepo.findChannelOverrides(channelId).stream()
                .map(roleService::serializeOverride)
                .toList();
        return Response.ok(overrides).build();
    }

    @PUT
    @Path("/channels/{channelId}/permissions/{roleId}")
    public Response upsertChannelOverride(
            @PathParam("channelId") String channelId,
            @PathParam("roleId") String roleId,
            OverrideRequest req
    ) {
        long allow = req.allow() != null ? Permission.fromNames(req.allow()) : 0L;
        long deny = req.deny() != null ? Permission.fromNames(req.deny()) : 0L;
        var override = roleService.upsertOverride(sc().getUserId(), channelId, roleId, allow, deny);
        return Response.ok(override).build();
    }

    @DELETE
    @Path("/channels/{channelId}/permissions/{roleId}")
    public Response deleteChannelOverride(
            @PathParam("channelId") String channelId,
            @PathParam("roleId") String roleId
    ) {
        roleService.deleteOverride(sc().getUserId(), channelId, roleId);
        return Response.noContent().build();
    }

    // --- DTOs ---

    public record CreateRoleRequest(String name, List<String> permissions, int position, String color) {}
    public record UpdateRoleRequest(String name, List<String> permissions, Integer position, String color) {}
    public record OverrideRequest(List<String> allow, List<String> deny) {}

    private FraySecurityContext sc() {
        return (FraySecurityContext) requestContext.getSecurityContext();
    }
}
