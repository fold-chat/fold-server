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

    /** Max external image download size in bytes (default 3MB) */
    @WithDefault("3145728")
    long maxProxyImageSize();

    /** Max markdown images per message (anti-amplification) */
    @WithDefault("5")
    int maxImagesPerMessage();
}
