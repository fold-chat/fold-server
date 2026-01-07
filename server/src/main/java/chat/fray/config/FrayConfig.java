package chat.fray.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "fray.db")
public interface FrayConfig {
    @WithDefault("fray.db")
    String path();

    Optional<String> url();

    Optional<String> authToken();

    @WithDefault("60")
    long syncInterval();
}
