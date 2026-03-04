package chat.kith.api;

import chat.kith.auth.KithSecurityContext;
import chat.kith.config.RuntimeConfigService;
import chat.kith.db.DatabaseService;
import chat.kith.event.Event;
import chat.kith.event.EventBus;
import chat.kith.event.EventType;
import chat.kith.event.Scope;
import chat.kith.security.Permission;
import chat.kith.security.PermissionService;
import chat.kith.service.AuditLogService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v0/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigResource {

    @Inject RuntimeConfigService runtimeConfig;
    @Inject DatabaseService db;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Inject AuditLogService auditLogService;
    @Context ContainerRequestContext requestContext;

    @GET
    public Response getConfig() {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);
        return Response.ok(runtimeConfig.getOverridableConfig()).build();
    }

    @PATCH
    public Response updateConfig(Map<String, String> body) {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);

        if (body == null || body.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "empty_body", "message", "No config provided")).build();
        }

        var updated = new LinkedHashMap<String, String>();

        for (var entry : body.entrySet()) {
            String key = entry.getKey();
            if (!runtimeConfig.isWhitelisted(key)) {
                return Response.status(400)
                        .entity(Map.of("error", "invalid_key", "message", "Key not allowed: " + key))
                        .build();
            }

            String value = entry.getValue() != null ? entry.getValue().trim() : null;
            if (value == null || value.isBlank()) {
                return Response.status(400)
                        .entity(Map.of("error", "invalid_value", "message", "Value required for key: " + key))
                        .build();
            }

            db.execute(
                    "INSERT OR REPLACE INTO server_config (key, value, updated_at) VALUES (?, ?, datetime('now'))",
                    key, value
            );
            updated.put(key, value);
        }

        if (!updated.isEmpty()) {
            runtimeConfig.refresh();
            eventBus.publish(Event.of(EventType.SERVER_CONFIG_UPDATE, updated, Scope.server()));
            auditLogService.log(sc.getUserId(), "SERVER_CONFIG_UPDATE", "server", null, Map.copyOf(updated));
        }

        return Response.ok(runtimeConfig.getOverridableConfig()).build();
    }

    private KithSecurityContext sc() {
        return (KithSecurityContext) requestContext.getSecurityContext();
    }
}
