package chat.kith.api;

import chat.kith.auth.AuthService;
import chat.kith.auth.SetupService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v0/setup")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class SetupResource {

    @Inject SetupService setupService;
    @Inject AuthService authService;

    @GET
    @Path("/status")
    public Map<String, Boolean> status() {
        return Map.of("setup_required", setupService.isSetupRequired());
    }

    @POST
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

    public record SetupRequest(String username, String password) {}
}
