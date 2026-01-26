package chat.fray.api;

import chat.fray.auth.FraySecurityContext;
import chat.fray.db.AuditLogRepository;
import chat.fray.security.Permission;
import chat.fray.security.PermissionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v0/audit-log")
@Produces(MediaType.APPLICATION_JSON)
public class AuditLogResource {

    @Inject
    AuditLogRepository auditLogRepository;

    @Inject
    PermissionService permissionService;

    @Context
    ContainerRequestContext requestContext;

    @GET
    public Response getAuditLog(
            @QueryParam("limit") @DefaultValue("50") int limit,
            @QueryParam("before") String beforeCursor,
            @QueryParam("action") String actionFilter
    ) {
        var sc = sc();
        
        // Require ADMINISTRATOR or MANAGE_SERVER
        if (!permissionService.hasServerPermission(sc.getUserId(), Permission.ADMINISTRATOR) &&
            !permissionService.hasServerPermission(sc.getUserId(), Permission.MANAGE_SERVER)) {
            return Response.status(403)
                    .entity(Map.of("error", "forbidden", "message", "Missing permission: ADMINISTRATOR or MANAGE_SERVER"))
                    .build();
        }

        if (limit < 1 || limit > 100) {
            limit = 50;
        }

        var entries = auditLogRepository.list(limit, beforeCursor, actionFilter);
        return Response.ok(Map.of("entries", entries)).build();
    }

    private FraySecurityContext sc() {
        return (FraySecurityContext) requestContext.getSecurityContext();
    }
}
