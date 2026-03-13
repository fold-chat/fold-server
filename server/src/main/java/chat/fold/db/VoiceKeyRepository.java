package chat.fold.db;

import com.fasterxml.uuid.Generators;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class VoiceKeyRepository {

    private static final int KEY_SIZE_BYTES = 32; // AES-256

    @Inject
    DatabaseService db;

    private final SecureRandom secureRandom = new SecureRandom();

    /** Create initial E2EE key for a voice channel */
    public Map<String, Object> createKey(String channelId) {
        byte[] key = new byte[KEY_SIZE_BYTES];
        secureRandom.nextBytes(key);
        String id = Generators.timeBasedEpochGenerator().generate().toString();
        db.execute("""
                INSERT INTO channel_voice_key (id, channel_id, encryption_key, key_index, created_at)
                VALUES (?, ?, ?, 0, datetime('now'))
                """, id, channelId, key);
        return Map.of("id", id, "channel_id", channelId, "encryption_key", key, "key_index", 0L);
    }

    /** Get the current (latest) key for a channel */
    public Optional<Map<String, Object>> getCurrentKey(String channelId) {
        var rows = db.query("""
                SELECT * FROM channel_voice_key
                WHERE channel_id = ?
                ORDER BY key_index DESC
                LIMIT 1
                """, channelId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Rotate key — generate new key with incremented key_index */
    public Map<String, Object> rotateKey(String channelId) {
        var current = getCurrentKey(channelId);
        long nextIndex = current.map(k -> ((Long) k.get("key_index")) + 1).orElse(0L);

        byte[] key = new byte[KEY_SIZE_BYTES];
        secureRandom.nextBytes(key);
        String id = Generators.timeBasedEpochGenerator().generate().toString();
        db.execute("""
                INSERT INTO channel_voice_key (id, channel_id, encryption_key, key_index, created_at)
                VALUES (?, ?, ?, ?, datetime('now'))
                """, id, channelId, key, nextIndex);
        return Map.of("id", id, "channel_id", channelId, "encryption_key", key, "key_index", nextIndex);
    }

    /** Delete all keys for a channel (channel deletion cleanup) */
    public void deleteByChannel(String channelId) {
        db.execute("DELETE FROM channel_voice_key WHERE channel_id = ?", channelId);
    }
}
