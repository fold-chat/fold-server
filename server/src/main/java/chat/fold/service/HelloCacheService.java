package chat.fold.service;

import chat.fold.config.FoldLiveKitConfig;
import chat.fold.config.FoldMediaConfig;
import chat.fold.config.RuntimeConfigService;
import chat.fold.db.*;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Caches shared (non-per-user) data used by the HELLO payload.
 * Each cache entry is lazily populated on first access and invalidated by mutation events.
 */
@ApplicationScoped
public class HelloCacheService {

    @Inject UserRepository userRepo;
    @Inject RoleRepository roleRepo;
    @Inject ChannelRepository channelRepo;
    @Inject CategoryRepository categoryRepo;
    @Inject EmojiRepository emojiRepo;
    @Inject DatabaseService db;
    @Inject RoleService roleService;
    @Inject RuntimeConfigService runtimeConfig;
    @Inject FoldMediaConfig mediaConfig;
    @Inject FoldLiveKitConfig liveKitConfig;
    @Inject LiveKitService liveKitService;
    @Inject MediaProcessingService mediaProcessingService;
    @Inject MaintenanceService maintenanceService;

    private static final Logger LOG = Logger.getLogger(HelloCacheService.class);
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    /** Warm all caches on startup so the first user doesn't pay the cost. */
    void onStartup(@Observes StartupEvent event) {
        long start = System.nanoTime();
        getMembers();
        getRoles();
        getChannels();
        getCategories();
        getCustomEmoji();
        getServerSettings();
        getCapabilities();
        LOG.infof("[BOOT] HelloCache ... OK (%dms)", (System.nanoTime() - start) / 1_000_000);
    }

    // Cached data
    private volatile List<Map<String, Object>> cachedMembers;
    private volatile List<Map<String, Object>> cachedRoles;
    private volatile List<Map<String, Object>> cachedChannels;
    private volatile List<Map<String, Object>> cachedCategories;
    private volatile List<Map<String, Object>> cachedCustomEmoji;
    private volatile Map<String, Object> cachedServerSettings;
    private volatile Map<String, Object> cachedCapabilities;

    // --- Getters (lazy init) ---

    public List<Map<String, Object>> getMembers() {
        var result = cachedMembers;
        if (result != null) return result;
        lock.writeLock().lock();
        try {
            if (cachedMembers == null) {
                cachedMembers = userRepo.listAllMembersSlim(false);
            }
            return cachedMembers;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Map<String, Object>> getRoles() {
        var result = cachedRoles;
        if (result != null) return result;
        lock.writeLock().lock();
        try {
            if (cachedRoles == null) {
                cachedRoles = roleRepo.findAll().stream()
                        .map(roleService::serializeRole)
                        .toList();
            }
            return cachedRoles;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Map<String, Object>> getChannels() {
        var result = cachedChannels;
        if (result != null) return result;
        lock.writeLock().lock();
        try {
            if (cachedChannels == null) {
                cachedChannels = channelRepo.listServerChannels();
            }
            return cachedChannels;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Map<String, Object>> getCategories() {
        var result = cachedCategories;
        if (result != null) return result;
        lock.writeLock().lock();
        try {
            if (cachedCategories == null) {
                cachedCategories = categoryRepo.listAll();
            }
            return cachedCategories;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<Map<String, Object>> getCustomEmoji() {
        var result = cachedCustomEmoji;
        if (result != null) return result;
        lock.writeLock().lock();
        try {
            if (cachedCustomEmoji == null) {
                cachedCustomEmoji = emojiRepo.listAll().stream().map(e -> {
                    var em = new LinkedHashMap<String, Object>();
                    em.put("id", e.get("id"));
                    em.put("name", e.get("name"));
                    em.put("url", "/api/v0/files/" + e.get("stored_name"));
                    em.put("uploader_id", e.get("uploader_id"));
                    return (Map<String, Object>) em;
                }).toList();
            }
            return cachedCustomEmoji;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Map<String, Object> getServerSettings() {
        var result = cachedServerSettings;
        if (result != null) return result;
        lock.writeLock().lock();
        try {
            if (cachedServerSettings == null) {
                var settingsRows = db.query("SELECT key, value FROM server_config WHERE key IN ('server_name', 'server_icon', 'server_description')");
                var settings = new LinkedHashMap<String, Object>();
                for (var row : settingsRows) {
                    settings.put((String) row.get("key"), row.get("value"));
                }
                settings.put("maintenance_enabled", maintenanceService.isEnabled());
                settings.put("maintenance_message", maintenanceService.getMessage());
                cachedServerSettings = settings;
            }
            return cachedServerSettings;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Map<String, Object> getCapabilities() {
        var result = cachedCapabilities;
        if (result != null) return result;
        lock.writeLock().lock();
        try {
            if (cachedCapabilities == null) {
                var capabilities = new LinkedHashMap<String, Object>();
                capabilities.put("voice_video", liveKitService.isEnabled());
                capabilities.put("voice_mode", liveKitService.getMode());
                capabilities.put("e2ee", runtimeConfig.getBoolean("fold.livekit.e2ee", liveKitConfig.e2ee()));
                capabilities.put("media_search", mediaConfig.klipyApiKey().filter(s -> !s.isBlank()).isPresent());
                var mediaProcessing = new LinkedHashMap<String, Object>();
                mediaProcessing.put("ffmpeg_available", mediaProcessingService.isFfmpegAvailable());
                mediaProcessing.put("video_mode", runtimeConfig.getString("fold.media-processing.video-mode",
                        mediaProcessingService.getVideoMode()));
                capabilities.put("media_processing", mediaProcessing);
                cachedCapabilities = capabilities;
            }
            return cachedCapabilities;
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** Look up a channel by ID from the cache. Falls back to DB if cache is empty. */
    public Optional<Map<String, Object>> findChannelById(String channelId) {
        var channels = getChannels();
        return channels.stream()
                .filter(c -> channelId.equals(c.get("id")))
                .findFirst();
    }

    // --- Invalidation ---

    public void invalidateMembers() { cachedMembers = null; }
    public void invalidateRoles() { cachedRoles = null; }
    public void invalidateChannels() { cachedChannels = null; }
    public void invalidateCategories() { cachedCategories = null; }
    public void invalidateCustomEmoji() { cachedCustomEmoji = null; }
    public void invalidateServerSettings() { cachedServerSettings = null; }
    public void invalidateCapabilities() { cachedCapabilities = null; }

    public void invalidateAll() {
        cachedMembers = null;
        cachedRoles = null;
        cachedChannels = null;
        cachedCategories = null;
        cachedCustomEmoji = null;
        cachedServerSettings = null;
        cachedCapabilities = null;
    }
}
