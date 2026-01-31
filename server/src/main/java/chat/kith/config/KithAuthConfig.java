package chat.kith.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "kith.auth")
public interface KithAuthConfig {
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

    Optional<String> adminUsername();

    Optional<String> adminPassword();
}
