package chat.fray.auth;

import chat.fray.config.FrayAuthConfig;
import chat.fray.db.InviteRepository;
import chat.fray.db.RoleRepository;
import chat.fray.db.SessionRepository;
import chat.fray.db.UserRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.NewCookie;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,32}$");
    private static final Duration REFRESH_TOKEN_TTL = Duration.ofDays(30);
    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject FrayAuthConfig authConfig;
    @Inject PasswordService passwordService;
    @Inject JwtService jwtService;
    @Inject UserRepository userRepo;
    @Inject SessionRepository sessionRepo;
    @Inject InviteRepository inviteRepo;
    @Inject RoleRepository roleRepo;

    // --- Registration ---

    public record RegisterRequest(String username, String password, String invite_code, String server_password) {}

    public record RegisterResult(String userId, String username) {}

    public RegisterResult register(RegisterRequest req) {
        // Validate username
        if (req.username() == null || !USERNAME_PATTERN.matcher(req.username()).matches()) {
            throw new AuthException("invalid_username", "Username must be 2-32 chars: letters, numbers, _ or -");
        }
        if (userRepo.existsByUsername(req.username())) {
            throw new AuthException("username_taken", "Username already taken");
        }

        // Validate password
        if (req.password() == null || req.password().length() < 8 || req.password().length() > 128) {
            throw new AuthException("invalid_password", "Password must be 8-128 characters");
        }

        // Validate invite or server password
        boolean usedInvite = false;
        String inviteCode = null;
        if (req.invite_code() != null && !req.invite_code().isBlank()) {
            var invite = inviteRepo.findValidByCode(req.invite_code())
                    .orElseThrow(() -> new AuthException("invalid_invite", "Invalid or expired invite code"));
            usedInvite = true;
            inviteCode = req.invite_code();
        } else if (req.server_password() != null && !req.server_password().isBlank()) {
            var joinPw = authConfig.joinPassword()
                    .filter(s -> !s.isBlank())
                    .orElseThrow(() -> new AuthException("no_join_method", "No join method available"));
            if (!req.server_password().equals(joinPw)) {
                throw new AuthException("invalid_server_password", "Incorrect server password");
            }
        } else {
            throw new AuthException("no_join_method", "Provide invite_code or server_password");
        }

        // Create user
        String userId = UUID.randomUUID().toString();
        String hash = passwordService.hash(req.password());
        userRepo.create(userId, req.username(), req.username(), hash);
        var defaultRole = roleRepo.findDefaultRole();
        String defaultRoleId = defaultRole.map(r -> (String) r.get("id")).orElse("member");
        userRepo.assignRole(userId, defaultRoleId);

        // Increment invite use count
        if (usedInvite) {
            inviteRepo.incrementUseCount(inviteCode);
        }

        return new RegisterResult(userId, req.username());
    }

    // --- Login ---

    public record LoginRequest(String username, String password) {}

    public record LoginResult(String userId, String username, String accessToken, String refreshToken, String sessionId) {}

    public LoginResult login(LoginRequest req) {
        var userOpt = userRepo.findByUsername(req.username());
        if (userOpt.isEmpty()) {
            throw new AuthException("invalid_credentials", "Invalid username or password");
        }
        var user = userOpt.get();
        String userId = (String) user.get("id");

        // Check lockout
        var lockedUntil = (String) user.get("locked_until");
        if (lockedUntil != null) {
            var lockExpiry = Instant.parse(lockedUntil);
            if (Instant.now().isBefore(lockExpiry)) {
                long retrySeconds = Duration.between(Instant.now(), lockExpiry).toSeconds();
                throw new LockedException(retrySeconds);
            }
            // Lock expired, reset
            userRepo.resetFailedLogin(userId);
        }

        // Verify password
        if (!passwordService.verify(req.password(), (String) user.get("password_hash"))) {
            userRepo.incrementFailedLogin(userId);
            long failCount = ((Long) user.get("failed_login_count")) + 1;
            if (failCount >= authConfig.lockoutThreshold()) {
                var lockUntil = Instant.now().plus(authConfig.lockoutDuration(), ChronoUnit.MINUTES).toString();
                userRepo.lockUser(userId, lockUntil);
            }
            throw new AuthException("invalid_credentials", "Invalid username or password");
        }

        // Reset failed login count on success
        Long failCount = (Long) user.get("failed_login_count");
        if (failCount != null && failCount > 0) {
            userRepo.resetFailedLogin(userId);
        }

        // Create session
        String sessionId = UUID.randomUUID().toString();
        String refreshToken = generateRefreshToken();
        String refreshHash = sha256(refreshToken);
        String expiresAt = Instant.now().plus(REFRESH_TOKEN_TTL).toString();

        sessionRepo.create(sessionId, userId, refreshHash, expiresAt, null);

        String accessToken = jwtService.issueAccessToken(userId, (String) user.get("username"));
        return new LoginResult(userId, (String) user.get("username"), accessToken, refreshToken, sessionId);
    }

    // --- Refresh ---

    public record RefreshResult(String accessToken, String refreshToken, String sessionId, String userId, String username) {}

    public RefreshResult refresh(String oldRefreshToken) {
        if (oldRefreshToken == null || oldRefreshToken.isBlank()) {
            throw new AuthException("invalid_refresh_token", "Missing refresh token");
        }

        String oldHash = sha256(oldRefreshToken);
        var sessionOpt = sessionRepo.findByRefreshTokenHash(oldHash);
        if (sessionOpt.isEmpty()) {
            throw new AuthException("invalid_refresh_token", "Invalid or expired refresh token");
        }

        var session = sessionOpt.get();
        String sessionId = (String) session.get("id");
        String userId = (String) session.get("user_id");

        // Look up user
        var user = userRepo.findById(userId)
                .orElseThrow(() -> new AuthException("invalid_refresh_token", "User not found"));

        // Rotate refresh token
        String newRefreshToken = generateRefreshToken();
        String newHash = sha256(newRefreshToken);
        String newExpiresAt = Instant.now().plus(REFRESH_TOKEN_TTL).toString();
        sessionRepo.updateRefreshToken(sessionId, newHash, newExpiresAt);

        String accessToken = jwtService.issueAccessToken(userId, (String) user.get("username"));
        return new RefreshResult(accessToken, newRefreshToken, sessionId, userId, (String) user.get("username"));
    }

    // --- Logout ---

    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) return;
        String hash = sha256(refreshToken);
        var session = sessionRepo.findByRefreshTokenHash(hash);
        session.ifPresent(s -> sessionRepo.delete((String) s.get("id")));
    }

    // --- Password Change ---

    public void changePassword(String userId, String currentPassword, String newPassword, String currentSessionId) {
        var user = userRepo.findById(userId)
                .orElseThrow(() -> new AuthException("not_found", "User not found"));

        if (!passwordService.verify(currentPassword, (String) user.get("password_hash"))) {
            throw new AuthException("invalid_credentials", "Current password is incorrect");
        }

        if (newPassword == null || newPassword.length() < 8 || newPassword.length() > 128) {
            throw new AuthException("invalid_password", "Password must be 8-128 characters");
        }

        userRepo.updatePasswordHash(userId, passwordService.hash(newPassword));
        // Revoke all other sessions
        if (currentSessionId != null) {
            sessionRepo.deleteAllForUserExcept(userId, currentSessionId);
        } else {
            sessionRepo.deleteAllForUser(userId);
        }
    }

    // --- Cookie helpers ---

    public NewCookie accessCookie(String token) {
        return new NewCookie.Builder("fray_access")
                .value(token)
                .path("/api")
                .maxAge((int) jwtService.getAccessTokenTtl().toSeconds())
                .httpOnly(true)
                .secure(!authConfig.dev())
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    public NewCookie refreshCookie(String token) {
        return new NewCookie.Builder("fray_refresh")
                .value(token)
                .path("/api/v0/auth")
                .maxAge((int) REFRESH_TOKEN_TTL.toSeconds())
                .httpOnly(true)
                .secure(!authConfig.dev())
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    public NewCookie clearAccessCookie() {
        return new NewCookie.Builder("fray_access")
                .value("")
                .path("/api")
                .maxAge(0)
                .httpOnly(true)
                .secure(!authConfig.dev())
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    public NewCookie clearRefreshCookie() {
        return new NewCookie.Builder("fray_refresh")
                .value("")
                .path("/api/v0/auth")
                .maxAge(0)
                .httpOnly(true)
                .secure(!authConfig.dev())
                .sameSite(NewCookie.SameSite.STRICT)
                .build();
    }

    // --- Helpers ---

    private static String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String input) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes()));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    // --- Exception types ---

    public static class AuthException extends RuntimeException {
        public final String code;
        public AuthException(String code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static class LockedException extends RuntimeException {
        public final long retryAfterSeconds;
        public LockedException(long retryAfterSeconds) {
            super("Account locked");
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }
}
