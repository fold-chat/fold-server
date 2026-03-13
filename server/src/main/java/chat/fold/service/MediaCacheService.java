package chat.fold.service;

import chat.fold.config.FoldFileConfig;
import chat.fold.config.FoldMediaConfig;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Optional;

@ApplicationScoped
public class MediaCacheService {

    private static final Logger LOG = Logger.getLogger(MediaCacheService.class);

    @Inject FoldFileConfig fileConfig;
    @Inject FoldMediaConfig mediaConfig;

    private Path cacheDir;

    void init(@Observes StartupEvent ev) {
        cacheDir = Path.of(fileConfig.dataDir(), "cache", "media");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOG.warn("Failed to create media cache directory", e);
        }
    }

    /** Package-private for testing. */
    void setCacheDir(Path dir) {
        this.cacheDir = dir;
    }

    /** Package-private for testing. */
    Path getCacheDir() {
        return cacheDir;
    }

    public Optional<CachedMedia> get(String url) {
        var hash = hash(url);
        var dataFile = cacheDir.resolve(hash);
        var metaFile = cacheDir.resolve(hash + ".ct");
        try {
            if (Files.exists(dataFile) && Files.exists(metaFile)) {
                // Check TTL — if expired, remove and return empty
                if (isExpired(dataFile)) {
                    Files.deleteIfExists(dataFile);
                    Files.deleteIfExists(metaFile);
                    return Optional.empty();
                }
                // Touch last-modified for LRU ordering
                dataFile.toFile().setLastModified(System.currentTimeMillis());
                var contentType = Files.readString(metaFile).trim();
                return Optional.of(new CachedMedia(Files.readAllBytes(dataFile), contentType));
            }
        } catch (IOException e) {
            LOG.debug("Cache read failed for " + hash, e);
        }
        return Optional.empty();
    }

    public void put(String url, byte[] data, String contentType) {
        var hash = hash(url);
        try {
            Files.write(cacheDir.resolve(hash), data);
            Files.writeString(cacheDir.resolve(hash + ".ct"), contentType);
        } catch (IOException e) {
            LOG.debug("Cache write failed for " + hash, e);
        }
        evictIfNeeded();
    }

    /** Evict expired entries + LRU eviction when over max size. */
    void evictIfNeeded() {
        long maxBytes = mediaConfig.maxCacheSize() * 1024L * 1024L;
        try (var stream = Files.list(cacheDir)) {
            var files = stream
                    .filter(p -> !p.getFileName().toString().endsWith(".ct"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .toList();

            // First pass: remove TTL-expired entries
            long total = 0;
            for (var f : files) {
                if (isExpired(f)) {
                    Files.deleteIfExists(f);
                    Files.deleteIfExists(Path.of(f + ".ct"));
                } else {
                    total += f.toFile().length();
                }
            }

            // Second pass: LRU eviction if still over budget
            if (total > maxBytes) {
                // Re-list since we deleted some
                try (var stream2 = Files.list(cacheDir)) {
                    var remaining = stream2
                            .filter(p -> !p.getFileName().toString().endsWith(".ct"))
                            .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                            .toList();
                    long currentTotal = remaining.stream().mapToLong(p -> p.toFile().length()).sum();
                    for (var f : remaining) {
                        if (currentTotal <= maxBytes) break;
                        long size = f.toFile().length();
                        Files.deleteIfExists(f);
                        Files.deleteIfExists(Path.of(f + ".ct"));
                        currentTotal -= size;
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("Cache eviction failed", e);
        }
    }

    /** Periodic cleanup of expired cache entries (every 30 minutes). */
    @Scheduled(every = "30m")
    void cleanExpiredEntries() {
        if (cacheDir == null) return;
        try (var stream = Files.list(cacheDir)) {
            stream.filter(p -> !p.getFileName().toString().endsWith(".ct"))
                    .filter(this::isExpired)
                    .forEach(f -> {
                        try {
                            Files.deleteIfExists(f);
                            Files.deleteIfExists(Path.of(f + ".ct"));
                        } catch (IOException e) {
                            LOG.debug("Failed to clean expired cache entry: " + f, e);
                        }
                    });
        } catch (IOException e) {
            LOG.debug("Scheduled cache cleanup failed", e);
        }
    }

    private boolean isExpired(Path dataFile) {
        long ttlHours = mediaConfig.cacheTtl();
        if (ttlHours <= 0) return false;
        long lastModified = dataFile.toFile().lastModified();
        long ageMs = System.currentTimeMillis() - lastModified;
        return ageMs > ttlHours * 3600_000L;
    }

    static String hash(String url) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record CachedMedia(byte[] data, String contentType) {}
}
