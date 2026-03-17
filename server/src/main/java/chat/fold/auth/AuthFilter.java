package chat.fold.auth;

import chat.fold.db.UserRepository;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import chat.fold.service.MaintenanceService;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {

    private static final String ACCESS_COOKIE = "fold_access";

    @Inject
    JwtService jwtService;

    @Inject
    UserRepository userRepo;

    @Inject
    MaintenanceService maintenanceService;

    @Inject
    PermissionService permissionService;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getPath(); // path without leading /api/v0 context
        String method = ctx.getMethod();

        if (isPublicPath(path, method)) return;

        Cookie cookie = ctx.getCookies().get(ACCESS_COOKIE);
        if (cookie == null || cookie.getValue().isBlank()) {
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(java.util.Map.of("error", "authentication_required"))
                    .build());
            return;
        }

        var claims = jwtService.verify(cookie.getValue());
        if (claims.isEmpty()) {
            ctx.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                    .entity(java.util.Map.of("error", "invalid_token"))
                    .build());
            return;
        }

        var c = claims.get();
        String userId = c.getSubject();

        // Check if user is banned
        if (userRepo.isBanned(userId)) {
            ctx.abortWith(Response.status(403)
                    .entity(java.util.Map.of("error", "banned", "message", "You have been banned"))
                    .build());
            return;
        }

        ctx.setSecurityContext(new FoldSecurityContext(userId, (String) c.get("usr")));

        // Block users who must change their password
        if (!isPasswordChangePath(path) && isPasswordMustChange(userId)) {
            ctx.abortWith(Response.status(403)
                    .entity(java.util.Map.of("error", "password_must_change", "message", "You must change your password"))
                    .build());
            return;
        }

        // Block non-admin users during maintenance mode
        if (maintenanceService.isEnabled() && !isAdminPath(path)) {
            if (!permissionService.hasServerPermission(userId, Permission.MANAGE_SERVER)) {
                ctx.abortWith(Response.status(503)
                        .entity(java.util.Map.of(
                                "error", "maintenance",
                                "message", maintenanceService.getMessage() != null
                                        ? maintenanceService.getMessage()
                                        : "Server is under maintenance"
                        ))
                        .build());
                return;
            }
        }
    }

    private boolean isPublicPath(String path, String method) {
        // Strip leading slash if present
        String p = path.startsWith("/") ? path.substring(1) : path;

        // Exact public paths
        if (p.equals("api/v0/auth/login")) return true;
        if (p.equals("api/v0/auth/register")) return true;
        if (p.equals("api/v0/auth/refresh")) return true;
        if (p.equals("api/v0/status")) return true;

        // Prefix public paths
        if (p.startsWith("api/v0/setup")) return true;
        if (p.startsWith("api/v0/avatars/default/")) return true;
        if (p.startsWith("api/v0/webhooks/")) return true;

        // Invite GET only (public info)
        if (p.startsWith("api/v0/invites/") && "GET".equals(method)) return true;

        // Non-API paths (static files, SPA fallback)
        if (!p.startsWith("api/")) return true;

        return false;
    }

    private boolean isAdminPath(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        return p.startsWith("api/v0/admin/");
    }

    /** Paths allowed when password_must_change is set */
    private boolean isPasswordChangePath(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        if (p.equals("api/v0/auth/force-change-password")) return true;
        if (p.equals("api/v0/auth/session")) return true; // logout
        return false;
    }

    private boolean isPasswordMustChange(String userId) {
        var user = userRepo.findById(userId);
        if (user.isEmpty()) return false;
        Long flag = (Long) user.get().get("password_must_change");
        return flag != null && flag != 0;
    }
}
