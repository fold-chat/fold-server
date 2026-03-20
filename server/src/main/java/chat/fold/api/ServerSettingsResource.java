package chat.fold.api;

import chat.fold.auth.FileService;
import chat.fold.auth.FoldSecurityContext;
import chat.fold.db.DatabaseService;
import chat.fold.db.FileRepository;
import chat.fold.event.Event;
import chat.fold.event.EventBus;
import chat.fold.event.EventType;
import chat.fold.event.Scope;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import chat.fold.service.AuditLogService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Path("/api/v0/settings")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ServerSettingsResource {

    private static final Set<String> PUBLIC_KEYS = Set.of("server_name", "server_icon", "server_description", "server_url");

    @Inject DatabaseService db;
    @Inject FileService fileService;
    @Inject FileRepository fileRepo;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Inject AuditLogService auditLogService;
    @Context ContainerRequestContext requestContext;

    /** Public endpoint — serves the server icon image (no auth required) */
    @GET
    @Path("/icon")
    public Response getIcon() {
        var rows = db.query("SELECT value FROM server_config WHERE key = 'server_icon'");
        if (rows.isEmpty() || rows.getFirst().get("value") == null) {
            return Response.status(404).build();
        }
        String iconUrl = (String) rows.getFirst().get("value");
        // URL is like /api/v0/files/<storedName>
        String storedName = iconUrl.substring(iconUrl.lastIndexOf('/') + 1);
        var meta = fileRepo.findByStoredName(storedName);
        var filePath = fileService.getFilePath(storedName);
        if (meta.isEmpty() || filePath.isEmpty()) {
            return Response.status(404).build();
        }
        try {
            byte[] data = Files.readAllBytes(filePath.get());
            String mimeType = (String) meta.get().get("mime_type");
            return Response.ok(data)
                    .type(mimeType)
                    .header("Cache-Control", "public, max-age=86400")
                    .build();
        } catch (IOException e) {
            return Response.status(500).build();
        }
    }

    @GET
    public Map<String, Object> getSettings() {
        var rows = db.query("SELECT key, value FROM server_config WHERE key IN ('server_name', 'server_icon', 'server_description', 'server_url')");
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

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }
}
