package chat.kith.config;

import chat.kith.db.DatabaseService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runtime-overridable config backed by server_config table.
 * In-memory cache refreshed on boot and after admin writes.
 * Use this for admin-overridable keys; use @ConfigMapping for bootstrap config.
 */
@ApplicationScoped
public class RuntimeConfigService {

    private static final Logger LOG = Logger.getLogger(RuntimeConfigService.class);

    /** Keys that admins are allowed to override via the config API */
    public static final Set<String> WHITELISTED_KEYS = Set.of(
            "kith.livekit.mode",
            "kith.livekit.url",
            "kith.livekit.api-key",
            "kith.livekit.api-secret",
            "kith.livekit.central-api-key",
            "kith.livekit.max-participants",
            "kith.livekit.e2ee",
            "kith.livekit.turn-enabled",
            "kith.media-processing.video-mode",
            "kith.media-processing.max-video-duration",
            "kith.media-processing.max-video-size",
            "kith.media-processing.max-image-size",
            "kith.media-processing.thumbnail-max-width",
            "kith.media-processing.hw-accel"
    );

    /** Keys whose values are obscured in GET responses */
    public static final Set<String> SENSITIVE_KEYS = Set.of(
            "kith.livekit.api-key",
            "kith.livekit.api-secret",
            "kith.livekit.central-api-key"
    );

    private final ConcurrentHashMap<String, String> cache = new ConcurrentHashMap<>();

    @Inject DatabaseService db;

    @PostConstruct
    void init() {
        refresh();
        LOG.infof("[BOOT] Runtime config ... OK (%d keys loaded)", cache.size());
    }

    /** Reload all kith.% keys from server_config into cache */
    public void refresh() {
        var rows = db.query("SELECT key, value FROM server_config WHERE key LIKE 'kith.%'");
        var newCache = new ConcurrentHashMap<String, String>();
        for (var row : rows) {
            String key = (String) row.get("key");
            String value = (String) row.get("value");
            if (key != null && value != null) {
                newCache.put(key, value);
            }
        }
        cache.clear();
        cache.putAll(newCache);
    }

    public String getString(String key, String defaultValue) {
        return cache.getOrDefault(key, defaultValue);
    }

    public int getInt(String key, int defaultValue) {
        var val = cache.get(key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            LOG.warnf("Invalid int for runtime config key '%s': %s", key, val);
            return defaultValue;
        }
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        var val = cache.get(key);
        if (val == null) return defaultValue;
        return "true".equalsIgnoreCase(val) || "1".equals(val);
    }

    /** Returns all whitelisted config key-value pairs (for admin API) */
    public Map<String, String> getOverridableConfig() {
        var result = new LinkedHashMap<String, String>();
        for (var key : WHITELISTED_KEYS) {
            result.put(key, cache.get(key));
        }
        return result;
    }

    /** Like getOverridableConfig() but replaces sensitive values with first 7 chars + "..." */
    public Map<String, String> getOverridableConfigObscured() {
        var result = new LinkedHashMap<String, String>();
        for (var key : WHITELISTED_KEYS) {
            String val = cache.get(key);
            if (val != null && !val.isBlank() && SENSITIVE_KEYS.contains(key)) {
                result.put(key, val.length() > 7 ? val.substring(0, 7) + "..." : val + "...");
            } else {
                result.put(key, val);
            }
        }
        return result;
    }

    /** Check if a key is in the whitelist */
    public boolean isWhitelisted(String key) {
        return WHITELISTED_KEYS.contains(key);
    }
}
