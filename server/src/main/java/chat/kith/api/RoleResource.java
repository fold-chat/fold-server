package chat.kith.api;

import chat.kith.auth.KithSecurityContext;
import chat.kith.db.RoleRepository;
import chat.kith.security.Permission;
import chat.kith.service.AuditLogService;
import chat.kith.service.RoleService;
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
    @Inject AuditLogService auditLogService;
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
        var role = roleService.createRole(sc().getUserId(), req.name().trim(), permissions, req.color());
        auditLogService.log(sc().getUserId(), "ROLE_CREATE", "role", (String) role.get("id"), Map.of("name", req.name()));
        return Response.status(201).entity(role).build();
    }

    @PATCH
    @Path("/roles/{id}")
    public Response updateRole(@PathParam("id") String id, UpdateRoleRequest req) {
        Long permissions = req.permissions() != null ? Permission.fromNames(req.permissions()) : null;
        var role = roleService.updateRole(sc().getUserId(), id, req.name(), permissions, req.color());
        auditLogService.log(sc().getUserId(), "ROLE_UPDATE", "role", id);
        return Response.ok(role).build();
    }

    @PATCH
    @Path("/roles/reorder")
    public Response reorderRoles(List<ReorderItem> items) {
        if (items == null || items.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "invalid_body", "message", "Items required")).build();
        }
        var roles = roleService.reorderRoles(sc().getUserId(), 
                items.stream().map(i -> new RoleRepository.IdPosition(i.id(), i.position())).toList());
        auditLogService.log(sc().getUserId(), "ROLE_REORDER", "role", null);
        return Response.ok(roles).build();
    }

    @DELETE
    @Path("/roles/{id}")
    public Response deleteRole(@PathParam("id") String id) {
        long userCount = roleService.deleteRole(sc().getUserId(), id);
        auditLogService.log(sc().getUserId(), "ROLE_DELETE", "role", id);
        return Response.ok(Map.of("deleted", true, "affected_users", userCount)).build();
    }

    @PUT
    @Path("/roles/{id}/default")
    public Response setDefaultRole(@PathParam("id") String id) {
        var role = roleService.setDefaultRole(sc().getUserId(), id);
        auditLogService.log(sc().getUserId(), "ROLE_SET_DEFAULT", "role", id);
        return Response.ok(role).build();
    }

    // --- DTOs ---

    public record CreateRoleRequest(String name, List<String> permissions, String color) {}
    public record UpdateRoleRequest(String name, List<String> permissions, String color) {}
    public record ReorderItem(String id, int position) {}

    private KithSecurityContext sc() {
        return (KithSecurityContext) requestContext.getSecurityContext();
    }
}
