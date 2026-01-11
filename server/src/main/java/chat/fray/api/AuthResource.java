package chat.fray.api;

import chat.fray.auth.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v0/auth")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AuthResource {

    @Inject AuthService authService;
    @Inject RateLimitService rateLimitService;
    @Context ContainerRequestContext requestContext;

    @POST
    @Path("/register")
    public Response register(AuthService.RegisterRequest req) {
        var ip = getClientIp();
        var policy = rateLimitService.resolvePolicy("register", RateLimitPolicy.REGISTER);
        var rl = rateLimitService.check("ip:" + ip + ":register", policy);
        setRateLimit(rl);
        if (!rl.allowed()) {
            return Response.status(429)
                    .entity(Map.of("error", "rate_limited", "retry_after", rl.retryAfter().toSeconds()))
                    .build();
        }

        try {
            var result = authService.register(req);

            // Auto-login after registration
            var loginResult = authService.login(new AuthService.LoginRequest(req.username(), req.password()));
            return Response.ok(Map.of("user_id", result.userId(), "username", result.username(), "expires_in", 900))
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
    @Path("/login")
    public Response login(AuthService.LoginRequest req) {
        var ip = getClientIp();
        var policy = rateLimitService.resolvePolicy("login", RateLimitPolicy.LOGIN);
        var rl = rateLimitService.check("ip:" + ip + ":login", policy);
        setRateLimit(rl);
        if (!rl.allowed()) {
            return Response.status(429)
                    .entity(Map.of("error", "rate_limited", "retry_after", rl.retryAfter().toSeconds()))
                    .build();
        }

        try {
            var result = authService.login(req);
            return Response.ok(Map.of("expires_in", 900))
                    .cookie(authService.accessCookie(result.accessToken()))
                    .cookie(authService.refreshCookie(result.refreshToken()))
                    .build();
        } catch (AuthService.LockedException e) {
            return Response.status(423)
                    .entity(Map.of("error", "account_locked", "retry_after", e.retryAfterSeconds))
                    .build();
        } catch (AuthService.AuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", e.code, "message", e.getMessage()))
                    .build();
        }
    }

    @POST
    @Path("/refresh")
    public Response refresh(@CookieParam("fray_refresh") Cookie refreshCookie) {
        var ip = getClientIp();
        var policy = rateLimitService.resolvePolicy("refresh", RateLimitPolicy.REFRESH);
        var rl = rateLimitService.check("ip:" + ip + ":refresh", policy);
        setRateLimit(rl);
        if (!rl.allowed()) {
            return Response.status(429)
                    .entity(Map.of("error", "rate_limited", "retry_after", rl.retryAfter().toSeconds()))
                    .build();
        }

        try {
            String token = refreshCookie != null ? refreshCookie.getValue() : null;
            var result = authService.refresh(token);
            return Response.ok(Map.of("expires_in", 900))
                    .cookie(authService.accessCookie(result.accessToken()))
                    .cookie(authService.refreshCookie(result.refreshToken()))
                    .build();
        } catch (AuthService.AuthException e) {
            return Response.status(Response.Status.UNAUTHORIZED)
                    .entity(Map.of("error", e.code, "message", e.getMessage()))
                    .cookie(authService.clearAccessCookie())
                    .cookie(authService.clearRefreshCookie())
                    .build();
        }
    }

    @DELETE
    @Path("/session")
    public Response logout(@CookieParam("fray_refresh") Cookie refreshCookie) {
        String token = refreshCookie != null ? refreshCookie.getValue() : null;
        authService.logout(token);
        return Response.noContent()
                .cookie(authService.clearAccessCookie())
                .cookie(authService.clearRefreshCookie())
                .build();
    }

    @PATCH
    @Path("/password")
    public Response changePassword(PasswordChangeRequest req) {
        var sc = (FraySecurityContext) requestContext.getSecurityContext();
        var userId = sc.getUserId();

        var policy = rateLimitService.resolvePolicy("password_change", RateLimitPolicy.PASSWORD_CHANGE);
        var rl = rateLimitService.check("user:" + userId + ":password_change", policy);
        setRateLimit(rl);
        if (!rl.allowed()) {
            return Response.status(429)
                    .entity(Map.of("error", "rate_limited", "retry_after", rl.retryAfter().toSeconds()))
                    .build();
        }

        try {
            authService.changePassword(userId, req.current_password(), req.new_password(), null);
            return Response.ok(Map.of("message", "Password changed"))
                    .cookie(authService.clearAccessCookie())
                    .cookie(authService.clearRefreshCookie())
                    .build();
        } catch (AuthService.AuthException e) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", e.code, "message", e.getMessage()))
                    .build();
        }
    }

    public record PasswordChangeRequest(String current_password, String new_password) {}

    private String getClientIp() {
        // Check X-Forwarded-For first for proxied requests
        var forwarded = requestContext.getHeaderString("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        // Fall back to remote address from Vert.x
        var remoteAddr = requestContext.getHeaderString("X-Real-IP");
        return remoteAddr != null ? remoteAddr : "unknown";
    }

    private void setRateLimit(RateLimitResult rl) {
        requestContext.setProperty(RateLimitFilter.RATE_LIMIT_RESULT_KEY, rl);
    }
}
