package chat.kith.auth;

import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;

public class KithSecurityContext implements SecurityContext {

    private final String userId;
    private final String username;

    public KithSecurityContext(String userId, String username) {
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
