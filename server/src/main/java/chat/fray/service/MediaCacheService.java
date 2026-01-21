package chat.fray.service;

import chat.fray.config.FrayFileConfig;
import chat.fray.config.FrayMediaConfig;
import io.quarkus.runtime.StartupEvent;
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

    @Inject FrayFileConfig fileConfig;
    @Inject FrayMediaConfig mediaConfig;

    private Path cacheDir;

    void init(@Observes StartupEvent ev) {
        cacheDir = Path.of(fileConfig.dataDir(), "cache", "media");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOG.warn("Failed to create media cache directory", e);
        }
    }

    public Optional<CachedMedia> get(String url) {
        var hash = hash(url);
        var dataFile = cacheDir.resolve(hash);
        var metaFile = cacheDir.resolve(hash + ".ct");
        try {
            if (Files.exists(dataFile) && Files.exists(metaFile)) {
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

    private void evictIfNeeded() {
        long maxBytes = mediaConfig.maxCacheSize() * 1024L * 1024L;
        try (var stream = Files.list(cacheDir)) {
            var files = stream
                    .filter(p -> !p.getFileName().toString().endsWith(".ct"))
                    .sorted(Comparator.comparingLong(p -> p.toFile().lastModified()))
                    .toList();
            long total = files.stream().mapToLong(p -> p.toFile().length()).sum();
            for (var f : files) {
                if (total <= maxBytes) break;
                long size = f.toFile().length();
                Files.deleteIfExists(f);
                Files.deleteIfExists(Path.of(f + ".ct"));
                total -= size;
            }
        } catch (IOException e) {
            LOG.warn("Cache eviction failed", e);
        }
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
