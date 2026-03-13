package chat.fold.api;

import chat.fold.auth.FoldSecurityContext;
import chat.fold.event.Event;
import chat.fold.event.EventBus;
import chat.fold.event.EventType;
import chat.fold.event.Scope;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import chat.fold.service.AuditLogService;
import chat.fold.service.MaintenanceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v0/admin/status")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {

    @Inject MaintenanceService maintenanceService;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Inject AuditLogService auditLogService;
    @Context ContainerRequestContext requestContext;

    @POST
    @Path("/disable")
    public Response disable(DisableRequest req) {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);

        String message = req != null ? req.message() : null;
        if (message != null && message.length() > 500) {
            return Response.status(400)
                    .entity(Map.of("error", "invalid_message", "message", "Maintenance message too long (max 500)"))
                    .build();
        }

        maintenanceService.disable(message);

        var data = new LinkedHashMap<String, Object>();
        data.put("maintenance_enabled", true);
        data.put("maintenance_message", message);
        eventBus.publish(Event.of(EventType.SERVER_SETTINGS_UPDATE, data, Scope.server()));

        auditLogService.log(sc.getUserId(), "MAINTENANCE_ENABLE", "server", null,
                message != null ? Map.of("message", message) : null);

        return Response.ok(Map.of("maintenance_enabled", true, "maintenance_message", message != null ? message : "")).build();
    }

    @POST
    @Path("/enable")
    public Response enable() {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);

        maintenanceService.enable();

        var data = new LinkedHashMap<String, Object>();
        data.put("maintenance_enabled", false);
        data.put("maintenance_message", null);
        eventBus.publish(Event.of(EventType.SERVER_SETTINGS_UPDATE, data, Scope.server()));

        auditLogService.log(sc.getUserId(), "MAINTENANCE_DISABLE", "server", null);

        return Response.ok(Map.of("maintenance_enabled", false)).build();
    }

    @GET
    public Response getStatus() {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);

        var result = new LinkedHashMap<String, Object>();
        result.put("maintenance_enabled", maintenanceService.isEnabled());
        result.put("maintenance_message", maintenanceService.getMessage());
        return Response.ok(result).build();
    }

    // --- DTOs ---

    public record DisableRequest(String message) {}

    // --- Helpers ---

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }
}
