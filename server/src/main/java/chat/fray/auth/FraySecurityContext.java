package chat.fray.auth;

import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;

public class FraySecurityContext implements SecurityContext {

    private final String userId;
    private final String username;

    public FraySecurityContext(String userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    @Override
    public Principal getUserPrincipal() {
        return () -> username;
    }

    @Override
    public boolean isUserInRole(String role) {
        return false; // Permission checks handled by PermissionService
    }

    @Override
    public boolean isSecure() {
        return true;
    }

    @Override
    public String getAuthenticationScheme() {
        return "COOKIE";
    }
}
