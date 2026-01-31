package chat.kith.api;

import chat.kith.auth.KithSecurityContext;
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
import java.util.Set;

@Path("/api/v0/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerSettingsResource {

    private static final Set<String> PUBLIC_KEYS = Set.of("server_name", "server_icon", "server_description");

    @Inject DatabaseService db;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Inject AuditLogService auditLogService;
    @Context ContainerRequestContext requestContext;

    @GET
    public Map<String, Object> getSettings() {
        var rows = db.query("SELECT key, value FROM server_config WHERE key IN ('server_name', 'server_icon', 'server_description')");
        var result = new LinkedHashMap<String, Object>();
        for (var row : rows) {
            result.put((String) row.get("key"), row.get("value"));
        }
        // Ensure all keys present
        for (var key : PUBLIC_KEYS) {
            result.putIfAbsent(key, null);
        }
        return result;
    }

    @PATCH
    public Response updateSettings(Map<String, Object> body) {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);

        if (body == null || body.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "empty_body", "message", "No settings provided")).build();
        }

        var updated = new LinkedHashMap<String, Object>();

        for (var entry : body.entrySet()) {
            String key = entry.getKey();
            if (!PUBLIC_KEYS.contains(key)) continue;

            String value = entry.getValue() != null ? entry.getValue().toString().trim() : null;

            // Validate server_name
            if ("server_name".equals(key)) {
                if (value == null || value.isBlank()) {
                    return Response.status(400).entity(Map.of("error", "invalid_name", "message", "Server name cannot be empty")).build();
                }
                if (value.length() > 100) {
                    return Response.status(400).entity(Map.of("error", "invalid_name", "message", "Server name too long (max 100)")).build();
                }
            }

            // Validate server_description
            if ("server_description".equals(key) && value != null && value.length() > 500) {
                return Response.status(400).entity(Map.of("error", "invalid_description", "message", "Description too long (max 500)")).build();
            }

            db.execute(
                    "INSERT OR REPLACE INTO server_config (key, value, updated_at) VALUES (?, ?, datetime('now'))",
                    key, value
            );
            updated.put(key, value);
        }

        if (!updated.isEmpty()) {
            eventBus.publish(Event.of(EventType.SERVER_SETTINGS_UPDATE, updated, Scope.server()));
            auditLogService.log(sc.getUserId(), "SERVER_SETTINGS_UPDATE", "server", null, updated);
        }

        // Return full settings
        return Response.ok(getSettings()).build();
    }

    private KithSecurityContext sc() {
        return (KithSecurityContext) requestContext.getSecurityContext();
    }
}
