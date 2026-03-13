package chat.fold.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.util.Optional;

@ConfigMapping(prefix = "fold.media")
public interface FoldMediaConfig {
    Optional<String> klipyApiKey();

    /** Max media cache size in MB */
    @WithDefault("512")
    long maxCacheSize();

    /** Cache entry TTL in hours. Entries older than this are evicted. 0 = no TTL. */
    @WithDefault("24")
    long cacheTtl();

    /** Whether YouTube video embedding is enabled */
    @WithDefault("true")
    boolean youtubeEmbed();
}
