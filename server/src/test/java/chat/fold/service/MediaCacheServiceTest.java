package chat.fold.service;

import chat.fold.config.FoldFileConfig;
import chat.fold.config.FoldMediaConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MediaCacheServiceTest {

    @TempDir Path tempDir;

    private MediaCacheService service;
    private long maxCacheSize = 1; // 1 MB
    private long cacheTtl = 24;    // 24 hours

    @BeforeEach
    void setup() {
        service = new MediaCacheService();
        service.fileConfig = new FoldFileConfig() {
            @Override public String dataDir() { return tempDir.toString(); }
            @Override public long maxUploadSize() { return 10_485_760; }
        };
        service.mediaConfig = new FoldMediaConfig() {
            @Override public Optional<String> klipyApiKey() { return Optional.empty(); }
            @Override public long maxCacheSize() { return maxCacheSize; }
            @Override public long cacheTtl() { return cacheTtl; }
            @Override public boolean youtubeEmbed() { return true; }
            @Override public long maxProxyImageSize() { return 3_145_728; }
            @Override public int maxImagesPerMessage() { return 5; }
        };

        // Initialize cache dir
        var cacheDir = tempDir.resolve("cache").resolve("media");
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        service.setCacheDir(cacheDir);
    }

    @Test
    void put_and_get_returns_cached_data() {
        service.put("https://example.com/test.gif", "gif-data".getBytes(), "image/gif");

        var result = service.get("https://example.com/test.gif");
        assertTrue(result.isPresent());
        assertEquals("image/gif", result.get().contentType());
        assertArrayEquals("gif-data".getBytes(), result.get().data());
    }

    @Test
    void get_returns_empty_for_missing_url() {
        var result = service.get("https://example.com/missing.gif");
        assertTrue(result.isEmpty());
    }

    @Test
    void get_returns_empty_for_expired_entry() throws IOException {
        service.put("https://example.com/old.gif", "old-data".getBytes(), "image/gif");

        // Manually set file modification time to 25 hours ago (TTL is 24h)
        var hash = MediaCacheService.hash("https://example.com/old.gif");
        var dataFile = service.getCacheDir().resolve(hash);
        long expiredTime = System.currentTimeMillis() - (25 * 3600_000L);
        dataFile.toFile().setLastModified(expiredTime);

        var result = service.get("https://example.com/old.gif");
        assertTrue(result.isEmpty());

        // Files should be cleaned up
        assertFalse(Files.exists(dataFile));
        assertFalse(Files.exists(service.getCacheDir().resolve(hash + ".ct")));
    }

    @Test
    void get_returns_data_when_ttl_disabled() throws IOException {
        // Create service with TTL=0 (disabled)
        cacheTtl = 0;
        setup();

        service.put("https://example.com/old.gif", "old-data".getBytes(), "image/gif");

        // Set file to very old
        var hash = MediaCacheService.hash("https://example.com/old.gif");
        var dataFile = service.getCacheDir().resolve(hash);
        dataFile.toFile().setLastModified(1000L);

        var result = service.get("https://example.com/old.gif");
        assertTrue(result.isPresent());
    }

    @Test
    void lru_eviction_removes_oldest_files_when_over_budget() throws IOException, InterruptedException {
        // Set max cache to very small (1 byte effectively triggers eviction for any real data)
        maxCacheSize = 0; // 0 MB = 0 bytes max
        setup();

        // Disable TTL so only LRU eviction applies
        cacheTtl = 0;
        service.mediaConfig = new FoldMediaConfig() {
            @Override public Optional<String> klipyApiKey() { return Optional.empty(); }
            @Override public long maxCacheSize() { return 0; }
            @Override public long cacheTtl() { return 0; }
            @Override public boolean youtubeEmbed() { return true; }
            @Override public long maxProxyImageSize() { return 3_145_728; }
            @Override public int maxImagesPerMessage() { return 5; }
        };

        service.put("https://example.com/1.gif", "data1".getBytes(), "image/gif");

        // After put, eviction runs. With 0 MB budget, all data files should be evicted.
        var hash = MediaCacheService.hash("https://example.com/1.gif");
        assertFalse(Files.exists(service.getCacheDir().resolve(hash)),
                "Data file should be evicted when over budget");
    }

    @Test
    void hash_is_deterministic() {
        var h1 = MediaCacheService.hash("https://example.com/test.gif");
        var h2 = MediaCacheService.hash("https://example.com/test.gif");
        assertEquals(h1, h2);
    }

    @Test
    void hash_differs_for_different_urls() {
        var h1 = MediaCacheService.hash("https://example.com/a.gif");
        var h2 = MediaCacheService.hash("https://example.com/b.gif");
        assertNotEquals(h1, h2);
    }

    @Test
    void cleanExpiredEntries_removes_old_files() throws IOException {
        service.put("https://example.com/stale.gif", "stale".getBytes(), "image/gif");

        var hash = MediaCacheService.hash("https://example.com/stale.gif");
        var dataFile = service.getCacheDir().resolve(hash);
        dataFile.toFile().setLastModified(System.currentTimeMillis() - (25 * 3600_000L));

        service.cleanExpiredEntries();

        assertFalse(Files.exists(dataFile));
        assertFalse(Files.exists(service.getCacheDir().resolve(hash + ".ct")));
    }

    @Test
    void cleanExpiredEntries_keeps_fresh_files() throws IOException {
        service.put("https://example.com/fresh.gif", "fresh".getBytes(), "image/gif");

        service.cleanExpiredEntries();

        var hash = MediaCacheService.hash("https://example.com/fresh.gif");
        assertTrue(Files.exists(service.getCacheDir().resolve(hash)));
    }
}
