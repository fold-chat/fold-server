package chat.fold.api;

import chat.fold.auth.FoldSecurityContext;
import chat.fold.security.PermissionService;
import chat.fold.service.AuditLogService;
import chat.fold.service.BackupService;
import chat.fold.service.BackupService.BackupArea;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Path("/api/v0/admin/backups")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BackupResource {

    private static final Pattern SAFE_FILENAME = Pattern.compile("[a-zA-Z0-9._-]+");

    @Inject BackupService backupService;
    @Inject PermissionService permissionService;
    @Inject AuditLogService auditLogService;
    @Context ContainerRequestContext requestContext;

    @POST
    public Response createBackup(CreateBackupRequest req) {
        var sc = sc();
        requireOwner(sc.getUserId());

        if (req == null || req.areas() == null || req.areas().isEmpty()) {
            return Response.status(400)
                    .entity(Map.of("error", "invalid_areas", "message", "At least one backup area required"))
                    .build();
        }

        Set<BackupArea> areas;
        try {
            areas = req.areas().stream()
                    .map(s -> BackupArea.valueOf(s.toUpperCase()))
                    .collect(Collectors.toSet());
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(Map.of("error", "invalid_areas", "message", "Valid areas: database, files, emojis"))
                    .build();
        }

        try {
            var result = backupService.createBackup(areas, req.password());
            var response = new LinkedHashMap<>(result.metadata());
            response.put("download_url", "/api/v0/admin/backups/" + result.filename());
            response.put("restore_note", "Restore is only possible on first server boot (before setup)");
            if (req.password() != null && !req.password().isBlank()) {
                response.put("password_note", "Password encrypts database only, not media files");
            }

            auditLogService.log(sc.getUserId(), "BACKUP_CREATE", "backup", result.filename(),
                    Map.of("areas", req.areas()));

            return Response.status(201).entity(response).build();
        } catch (IOException e) {
            return Response.status(500)
                    .entity(Map.of("error", "backup_failed", "message", e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/estimate")
    public Response estimate() {
        requireOwner(sc().getUserId());
        var est = backupService.estimateSize();
        return Response.ok(Map.of(
                "database", est.database(),
                "files", est.files(),
                "emojis", est.emojis()
        )).build();
    }

    @GET
    public Response list() {
        requireOwner(sc().getUserId());
        try {
            return Response.ok(backupService.listBackups()).build();
        } catch (IOException e) {
            return Response.status(500)
                    .entity(Map.of("error", "list_failed", "message", e.getMessage()))
                    .build();
        }
    }

    @GET
    @Path("/{filename}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@PathParam("filename") String filename) {
        var sc = sc();
        requireOwner(sc.getUserId());

        if (!SAFE_FILENAME.matcher(filename).matches()) {
            return Response.status(400)
                    .entity(Map.of("error", "invalid_filename")).type(MediaType.APPLICATION_JSON_TYPE)
                    .build();
        }

        var path = backupService.getBackupPath(filename);
        if (path.isEmpty()) {
            return Response.status(404)
                    .entity(Map.of("error", "not_found")).type(MediaType.APPLICATION_JSON_TYPE)
                    .build();
        }

        auditLogService.log(sc.getUserId(), "BACKUP_DOWNLOAD", "backup", filename);

        try {
            return Response.ok(Files.readAllBytes(path.get()))
                    .type("application/gzip")
                    .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                    .header("Content-Length", Files.size(path.get()))
                    .build();
        } catch (IOException e) {
            return Response.status(500)
                    .entity(Map.of("error", "download_failed")).type(MediaType.APPLICATION_JSON_TYPE)
                    .build();
        }
    }

    @DELETE
    @Path("/{filename}")
    public Response delete(@PathParam("filename") String filename) {
        var sc = sc();
        requireOwner(sc.getUserId());

        if (!SAFE_FILENAME.matcher(filename).matches()) {
            return Response.status(400)
                    .entity(Map.of("error", "invalid_filename"))
                    .build();
        }

        try {
            if (backupService.deleteBackup(filename)) {
                auditLogService.log(sc.getUserId(), "BACKUP_DELETE", "backup", filename);
                return Response.noContent().build();
            }
            return Response.status(404)
                    .entity(Map.of("error", "not_found"))
                    .build();
        } catch (IOException e) {
            return Response.status(500)
                    .entity(Map.of("error", "delete_failed", "message", e.getMessage()))
                    .build();
        }
    }

    // --- DTOs ---

    public record CreateBackupRequest(List<String> areas, String password) {}

    // --- Helpers ---

    private void requireOwner(String userId) {
        if (!permissionService.isOwner(userId)) {
            throw new jakarta.ws.rs.ForbiddenException();
        }
    }

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }
}
