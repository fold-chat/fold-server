package chat.fold.api;

import chat.fold.auth.AuthService;
import chat.fold.auth.SetupService;
import chat.fold.service.BackupService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Path("/api/v0/setup")
@Produces(MediaType.APPLICATION_JSON)
public class SetupResource {

    private static final AtomicBoolean RESTORE_IN_PROGRESS = new AtomicBoolean(false);

    @Inject SetupService setupService;
    @Inject AuthService authService;
    @Inject BackupService backupService;

    @GET
    @Path("/status")
    public Map<String, Boolean> status() {
        return Map.of("setup_required", setupService.isSetupRequired());
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response setup(SetupRequest req) {
        try {
            String userId = setupService.createAdmin(req.username(), req.password());

            // Auto-login
            var loginResult = authService.login(new AuthService.LoginRequest(req.username(), req.password()));
            return Response.ok(Map.of("user_id", userId, "username", req.username()))
                    .cookie(authService.accessCookie(loginResult.accessToken()))
                    .cookie(authService.refreshCookie(loginResult.refreshToken()))
                    .build();
        } catch (AuthService.AuthException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.code, "message", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/restore")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response restore(@RestForm("backup") FileUpload backup,
                            @RestForm("password") String password) {
        if (!setupService.isSetupRequired()) {
            return Response.status(400)
                    .entity(Map.of("error", "setup_complete", "message", "Restore only works on a fresh server"))
                    .build();
        }

        if (backup == null) {
            return Response.status(400)
                    .entity(Map.of("error", "missing_backup", "message", "Backup file required"))
                    .build();
        }

        if (!RESTORE_IN_PROGRESS.compareAndSet(false, true)) {
            return Response.status(409)
                    .entity(Map.of("error", "restore_in_progress", "message", "A restore is already in progress"))
                    .build();
        }

        try (var inputStream = Files.newInputStream(backup.uploadedFile())) {
            var result = backupService.restore(inputStream, password);
            return Response.ok(result).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(Map.of("error", "restore_failed", "message", e.getMessage()))
                    .build();
        } catch (IOException e) {
            return Response.status(500)
                    .entity(Map.of("error", "restore_failed", "message", e.getMessage()))
                    .build();
        } finally {
            RESTORE_IN_PROGRESS.set(false);
        }
    }

    public record SetupRequest(String username, String password) {}
}
