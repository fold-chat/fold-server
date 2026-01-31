package chat.kith.api;

import chat.kith.auth.*;
import chat.kith.db.UserRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v0")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class UserResource {

    @Inject UserRepository userRepo;
    @Inject PasswordService passwordService;
    @Inject RateLimitService rateLimitService;
    @Context ContainerRequestContext requestContext;

    @GET
    @Path("/users/@me")
    public Response me() {
        var sc = sc();
        return userRepo.findById(sc.getUserId())
                .map(u -> Response.ok(publicProfile(u, true)).build())
                .orElse(Response.status(404).entity(Map.of("error", "not_found")).build());
    }

    @PATCH
    @Path("/users/@me")
    public Response updateMe(UpdateProfileRequest req) {
        var sc = sc();
        var policy = rateLimitService.resolvePolicy("profile_update", RateLimitPolicy.PROFILE_UPDATE);
        var rl = rateLimitService.check("user:" + sc.getUserId() + ":profile_update", policy);
        requestContext.setProperty(RateLimitFilter.RATE_LIMIT_RESULT_KEY, rl);
        if (!rl.allowed()) {
            return Response.status(429)
                    .entity(Map.of("error", "rate_limited", "retry_after", rl.retryAfter().toSeconds()))
                    .build();
        }

        var existing = userRepo.findById(sc.getUserId());
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var user = existing.get();

        String displayName = req.display_name() != null ? req.display_name() : (String) user.get("display_name");
        String bio = req.bio() != null ? req.bio() : (String) user.get("bio");
        String statusPref = req.status_preference() != null ? req.status_preference() : (String) user.get("status_preference");
        String statusText = req.status_text() != null ? req.status_text() : (String) user.get("status_text");
        String avatarUrl = req.avatar_url() != null ? req.avatar_url() : (String) user.get("avatar_url");

        userRepo.updateProfile(sc.getUserId(), displayName, bio, statusPref, statusText, avatarUrl);

        return userRepo.findById(sc.getUserId())
                .map(u -> Response.ok(publicProfile(u, true)).build())
                .orElse(Response.status(500).build());
    }

    @GET
    @Path("/users/{id}")
    public Response getUser(@PathParam("id") String id) {
        return userRepo.findById(id)
                .map(u -> Response.ok(publicProfile(u, false)).build())
                .orElse(Response.status(404).entity(Map.of("error", "not_found")).build());
    }

    @DELETE
    @Path("/users/@me")
    public Response deleteMe(DeleteRequest req) {
        var sc = sc();
        var user = userRepo.findById(sc.getUserId());
        if (user.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }

        if (req.password() == null || !passwordService.verify(req.password(), (String) user.get().get("password_hash"))) {
            return Response.status(400)
                    .entity(Map.of("error", "invalid_credentials", "message", "Password required"))
                    .build();
        }

        userRepo.softDelete(sc.getUserId());
        return Response.noContent().build();
    }

    // --- DTOs ---

    public record UpdateProfileRequest(String display_name, String bio, String status_preference, String status_text, String avatar_url) {}
    public record DeleteRequest(String password) {}

    // --- Helpers ---

    private KithSecurityContext sc() {
        return (KithSecurityContext) requestContext.getSecurityContext();
    }

    private Map<String, Object> publicProfile(Map<String, Object> user, boolean full) {
        var profile = new LinkedHashMap<String, Object>();
        profile.put("id", user.get("id"));
        profile.put("username", user.get("username"));
        profile.put("display_name", user.get("display_name"));
        profile.put("avatar_url", user.get("avatar_url"));
        profile.put("status_preference", user.get("status_preference"));
        profile.put("status_text", user.get("status_text"));
        profile.put("bio", user.get("bio"));
        profile.put("created_at", user.get("created_at"));
        profile.put("last_seen_at", user.get("last_seen_at"));
        if (full) {
            // Include roles for own profile
            var roles = userRepo.getUserRoles((String) user.get("id"));
            profile.put("roles", roles.stream().map(r -> r.get("name")).toList());
        }
        return profile;
    }
}
