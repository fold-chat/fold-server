package chat.fray.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "fray.rate-limit")
public interface FrayRateLimitConfig {
    @WithDefault("true")
    boolean enabled();

    Optional<String> login();
    Optional<String> register();
    Optional<String> inviteJoin();
    Optional<String> refresh();
    Optional<String> profileUpdate();
    Optional<String> passwordChange();
    Optional<String> search();
    Optional<String> mediaSearch();
    Optional<String> voiceToken();
    Optional<String> voiceState();
    Optional<String> voiceModeration();
}
