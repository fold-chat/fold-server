package chat.fold.api;

import chat.fold.auth.FoldSecurityContext;
import chat.fold.config.RuntimeConfigService;
import chat.fold.db.DatabaseService;
import chat.fold.event.Event;
import chat.fold.event.EventBus;
import chat.fold.event.EventType;
import chat.fold.event.Scope;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import chat.fold.service.AuditLogService;
import chat.fold.service.LiveKitService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Path("/api/v0/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigResource {

    private static final Set<String> RECONFIGURE_KEYS = Set.of(
            "fold.livekit.mode", "fold.livekit.central-api-key",
            "fold.livekit.url", "fold.livekit.api-key", "fold.livekit.api-secret"
    );

    @Inject RuntimeConfigService runtimeConfig;
    @Inject DatabaseService db;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Inject AuditLogService auditLogService;
    @Inject LiveKitService liveKitService;
    @Context ContainerRequestContext requestContext;

    @GET
    public Response getConfig() {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);
        return Response.ok(runtimeConfig.getOverridableConfigObscured()).build();
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

            // Trigger LiveKit reconfigure if mode/key changed
            if (updated.keySet().stream().anyMatch(RECONFIGURE_KEYS::contains)) {
                liveKitService.reconfigure();
            }
        }

        return Response.ok(runtimeConfig.getOverridableConfigObscured()).build();
    }

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }
}
