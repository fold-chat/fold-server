package chat.kith.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "kith.media")
public interface KithMediaConfig {
    Optional<String> klipyApiKey();

    /** Max media cache size in MB */
    @WithDefault("512")
    long maxCacheSize();
}
