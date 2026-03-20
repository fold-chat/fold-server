package chat.fold.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "fold.auth")
public interface FoldAuthConfig {
    Optional<String> jwtSecret();

    Optional<String> joinPassword();

    @WithDefault("true")
    boolean inviteEnabled();

    @WithDefault("20")
    int lockoutThreshold();

    /** Lockout duration in minutes */
    @WithDefault("1440")
    long lockoutDuration();

    @WithDefault("false")
    boolean dev();

    /** Allow non-TLS cookies. For deployments without HTTPS. */
    @WithDefault("false")
    boolean insecure();

    Optional<String> adminUsername();

    Optional<String> adminPassword();
}
