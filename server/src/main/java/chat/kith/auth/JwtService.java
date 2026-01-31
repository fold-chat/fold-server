package chat.kith.auth;

import chat.kith.config.KithAuthConfig;
import chat.kith.db.DatabaseService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.quarkus.runtime.annotations.RegisterForReflection;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;

@ApplicationScoped
@RegisterForReflection(classNames = {
        "io.jsonwebtoken.impl.DefaultJwtBuilder",
        "io.jsonwebtoken.impl.DefaultJwtParserBuilder",
        "io.jsonwebtoken.impl.DefaultJwtHeaderBuilder",
        "io.jsonwebtoken.impl.DefaultClaimsBuilder",
        "io.jsonwebtoken.impl.security.StandardSecureDigestAlgorithms",
        "io.jsonwebtoken.impl.security.StandardEncryptionAlgorithms",
        "io.jsonwebtoken.impl.security.StandardKeyAlgorithms",
        "io.jsonwebtoken.impl.io.StandardCompressionAlgorithms",
        "io.jsonwebtoken.jackson.io.JacksonSerializer",
        "io.jsonwebtoken.jackson.io.JacksonDeserializer"
})
public class JwtService {

    private static final Logger LOG = Logger.getLogger(JwtService.class);
    private static final Duration ACCESS_TOKEN_TTL = Duration.ofMinutes(15);
    private static final String CONFIG_KEY = "jwt_secret";

    @Inject
    KithAuthConfig authConfig;

    @Inject
    DatabaseService db;

    private SecretKey signingKey;

    @PostConstruct
    void init() {
        String secret = authConfig.jwtSecret()
                .filter(s -> !s.isBlank())
                .orElseGet(this::loadOrGenerateSecret);
        signingKey = new SecretKeySpec(Base64.getDecoder().decode(secret), "HmacSHA256");
        LOG.info("[BOOT] JWT ... OK");
    }

    /** Issue access token with userId as subject, username as claim */
    public String issueAccessToken(String userId, String username) {
        var now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim("usr", username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ACCESS_TOKEN_TTL)))
                .signWith(signingKey)
                .compact();
    }

    /** Verify and parse access token. Returns claims or empty if invalid/expired. */
    public Optional<Claims> verify(String token) {
        try {
            var claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(claims);
        } catch (Exception e) {
            LOG.warnf("JWT verify failed: %s — %s", e.getClass().getSimpleName(), e.getMessage());
            return Optional.empty();
        }
    }

    public Duration getAccessTokenTtl() {
        return ACCESS_TOKEN_TTL;
    }

    private String loadOrGenerateSecret() {
        // Try to load from server_config
        var rows = db.query("SELECT value FROM server_config WHERE key = ?", CONFIG_KEY);
        if (!rows.isEmpty()) {
            var value = (String) rows.getFirst().get("value");
            if (value != null && !value.isBlank()) return value;
        }

        // Generate new secret
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String encoded = Base64.getEncoder().encodeToString(keyBytes);

        db.execute(
                "INSERT OR REPLACE INTO server_config (key, value, updated_at) VALUES (?, ?, datetime('now'))",
                CONFIG_KEY, encoded
        );
        LOG.info("Generated new JWT secret (persisted to server_config)");
        return encoded;
    }
}
