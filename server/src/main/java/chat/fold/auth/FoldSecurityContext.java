package chat.fold.auth;

import jakarta.ws.rs.core.SecurityContext;
import java.security.Principal;

public class FoldSecurityContext implements SecurityContext {

    private final String userId;
    private final String username;
    private final boolean isBot;

    public FoldSecurityContext(String userId, String username) {
        this(userId, username, false);
    }

    public FoldSecurityContext(String userId, String username, boolean isBot) {
        this.userId = userId;
        this.username = username;
        this.isBot = isBot;
    }

    public String getUserId() {
        return userId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isBot() {
        return isBot;
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
