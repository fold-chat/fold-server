package chat.fold.api;

import chat.fold.auth.AuthService;
import chat.fold.auth.FoldSecurityContext;
import chat.fold.db.SessionRepository;
import chat.fold.db.UserRepository;
import chat.fold.event.Event;
import chat.fold.event.EventBus;
import chat.fold.event.EventType;
import chat.fold.event.Scope;
import chat.fold.event.SessionRegistry;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import chat.fold.service.AuditLogService;
import chat.fold.service.RoleService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v0/members")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ModerationResource {

    @Inject AuthService authService;
    @Inject UserRepository userRepo;
    @Inject SessionRepository sessionRepo;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Inject SessionRegistry sessionRegistry;
    @Inject RoleService roleService;
    @Inject AuditLogService auditLogService;
    @Context ContainerRequestContext requestContext;

    @POST
    @Path("/{id}/ban")
    public Response ban(@PathParam("id") String targetId, BanRequest req) {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.BAN_MEMBERS);

        if (targetId.equals(sc.getUserId())) {
            return Response.status(400).entity(Map.of("error", "cannot_ban_self", "message", "Cannot ban yourself")).build();
        }

        var target = userRepo.findById(targetId);
        if (target.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }

        if (permissionService.isOwner(targetId)) {
            return Response.status(403).entity(Map.of("error", "cannot_ban_owner", "message", "Cannot ban the owner")).build();
        }

        String reason = req != null ? req.reason() : null;
        userRepo.ban(targetId, sc.getUserId(), reason);

        // Revoke all sessions
        sessionRepo.deleteAllForUser(targetId);

        // Close WebSocket connections
        closeWebSocketConnections(targetId);

        // Publish event
        var data = new java.util.LinkedHashMap<String, Object>();
        data.put("user_id", targetId);
        data.put("banned_by", sc.getUserId());
        if (reason != null) data.put("reason", reason);
        eventBus.publish(Event.of(EventType.MEMBER_BAN, data, Scope.server()));

        // Audit log
        var details = new java.util.HashMap<String, Object>();
        if (reason != null) details.put("reason", reason);
        auditLogService.log(sc.getUserId(), "MEMBER_BAN", "user", targetId, details);

        return Response.noContent().build();
    }

    @DELETE
    @Path("/{id}/ban")
    public Response unban(@PathParam("id") String targetId) {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.BAN_MEMBERS);

        var target = userRepo.findById(targetId);
        if (target.isEmpty()) {
            // Also check banned users (findById filters deleted_at but not banned)
            if (!userRepo.isBanned(targetId)) {
                return Response.status(404).entity(Map.of("error", "not_found")).build();
            }
        }

        userRepo.unban(targetId);

        eventBus.publish(Event.of(EventType.MEMBER_UNBAN, Map.of(
                "user_id", targetId,
                "unbanned_by", sc.getUserId()
        ), Scope.server()));

        // Audit log
        auditLogService.log(sc.getUserId(), "MEMBER_UNBAN", "user", targetId);

        return Response.noContent().build();
    }

    @GET
    public Response listMembers() {
        var sc = sc();
        boolean includeBanned = permissionService.hasServerPermission(sc.getUserId(), Permission.BAN_MEMBERS);
        return Response.ok(userRepo.listMembers(includeBanned)).build();
    }

    // --- Password Reset ---

    @POST
    @Path("/{id}/reset-password")
    public Response resetPassword(@PathParam("id") String targetId, ResetPasswordRequest req) {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.RESET_PASSWORDS);

        if (targetId.equals(sc.getUserId())) {
            return Response.status(400).entity(Map.of("error", "cannot_reset_self", "message", "Cannot reset your own password")).build();
        }

        var target = userRepo.findById(targetId);
        if (target.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }

        if (permissionService.isOwner(targetId)) {
            return Response.status(403).entity(Map.of("error", "cannot_reset_owner", "message", "Cannot reset the owner's password")).build();
        }

        try {
            authService.adminResetPassword(targetId, req.new_password());
        } catch (AuthService.AuthException e) {
            return Response.status(400).entity(Map.of("error", e.code, "message", e.getMessage())).build();
        }

        // Close WebSocket connections
        closeWebSocketConnections(targetId);

        auditLogService.log(sc.getUserId(), "PASSWORD_RESET", "user", targetId);

        return Response.noContent().build();
    }

    // --- Role assignment ---

    @PUT
    @Path("/{userId}/roles/{roleId}")
    public Response assignRole(@PathParam("userId") String userId, @PathParam("roleId") String roleId) {
        roleService.assignRole(sc().getUserId(), userId, roleId);
        auditLogService.log(sc().getUserId(), "ROLE_ASSIGN", "user", userId, Map.of("role_id", roleId));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{userId}/roles/{roleId}")
    public Response removeRole(@PathParam("userId") String userId, @PathParam("roleId") String roleId) {
        roleService.removeRole(sc().getUserId(), userId, roleId);
        auditLogService.log(sc().getUserId(), "ROLE_REMOVE", "user", userId, Map.of("role_id", roleId));
        return Response.noContent().build();
    }

    // --- DTOs ---

    public record BanRequest(String reason) {}
    public record ResetPasswordRequest(String new_password) {}

    // --- Helpers ---

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }

    private void closeWebSocketConnections(String userId) {
        var connections = sessionRegistry.getConnections(userId);
        for (var conn : connections) {
            conn.close().subscribe().with(ok -> {}, err -> {});
        }
    }
}
