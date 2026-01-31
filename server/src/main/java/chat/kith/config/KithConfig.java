package chat.kith.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "kith.db")
public interface KithConfig {
@WithDefault("kith.db")
    String path();

    Optional<String> url();

    Optional<String> authToken();

    @WithDefault("60")
    long syncInterval();
}
