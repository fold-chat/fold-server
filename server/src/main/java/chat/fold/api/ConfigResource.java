package chat.fold.api;

import chat.fold.auth.FoldSecurityContext;
import chat.fold.config.FoldLiveKitConfig;
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
    @Inject FoldLiveKitConfig liveKitConfig;
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

        // Validate mode prerequisites before saving
        String newMode = body.get("fold.livekit.mode");
        if (newMode != null) {
            var modeError = validateModePrerequisites(newMode, body);
            if (modeError != null) return modeError;
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

    /**
     * Validate that required bootstrap/runtime config exists for the target mode.
     * Returns an error Response if validation fails, null if OK.
     */
    private Response validateModePrerequisites(String mode, Map<String, String> body) {
        return switch (mode) {
            case "managed" -> {
                if (liveKitConfig.centralUrl().filter(s -> !s.isBlank()).isEmpty()) {
                    yield Response.status(400).entity(Map.of("error", "missing_config",
                            "message", "fold.livekit.central-url must be set (env FOLD_CENTRAL_URL)")).build();
                }
                if (liveKitConfig.webhookUrl().filter(s -> !s.isBlank()).isEmpty()) {
                    yield Response.status(400).entity(Map.of("error", "missing_config",
                            "message", "fold.livekit.webhook-url must be set (env FOLD_LIVEKIT_WEBHOOK_URL)")).build();
                }
                String apiKey = body.getOrDefault("fold.livekit.central-api-key",
                        runtimeConfig.getString("fold.livekit.central-api-key", null));
                if (apiKey == null || apiKey.isBlank()) {
                    yield Response.status(400).entity(Map.of("error", "missing_config",
                            "message", "fold.livekit.central-api-key is required for managed mode")).build();
                }
                yield null;
            }
            case "external" -> {
                String url = body.getOrDefault("fold.livekit.url",
                        runtimeConfig.getString("fold.livekit.url", liveKitConfig.url().orElse(null)));
                String key = body.getOrDefault("fold.livekit.api-key",
                        runtimeConfig.getString("fold.livekit.api-key", liveKitConfig.apiKey().orElse(null)));
                String secret = body.getOrDefault("fold.livekit.api-secret",
                        runtimeConfig.getString("fold.livekit.api-secret", liveKitConfig.apiSecret().orElse(null)));
                if (url == null || url.isBlank() || key == null || key.isBlank() || secret == null || secret.isBlank()) {
                    yield Response.status(400).entity(Map.of("error", "missing_config",
                            "message", "url, api-key, and api-secret are required for external mode")).build();
                }
                yield null;
            }
            case "off", "embedded" -> null;
            default -> Response.status(400).entity(Map.of("error", "invalid_mode",
                    "message", "Invalid mode: " + mode + ". Must be off, embedded, external, or managed")).build();
        };
    }

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }
}
