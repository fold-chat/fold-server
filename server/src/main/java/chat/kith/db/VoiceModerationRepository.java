package chat.kith.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class VoiceModerationRepository {

    @Inject
    DatabaseService db;

    public List<Map<String, Object>> findAll() {
        return db.query("SELECT * FROM voice_moderation");
    }

    public Optional<Map<String, Object>> findByUser(String userId) {
        var rows = db.query("SELECT * FROM voice_moderation WHERE user_id = ?", userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void setServerMute(String userId, boolean muted) {
        db.execute("""
                INSERT INTO voice_moderation (user_id, server_mute)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET server_mute = excluded.server_mute
                """, userId, muted ? 1 : 0);
    }

    public void setServerDeaf(String userId, boolean deaf) {
        db.execute("""
                INSERT INTO voice_moderation (user_id, server_deaf)
                VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET server_deaf = excluded.server_deaf
                """, userId, deaf ? 1 : 0);
    }

    /** Remove row when both flags are 0 */
    public void clearIfClean(String userId) {
        db.execute("DELETE FROM voice_moderation WHERE user_id = ? AND server_mute = 0 AND server_deaf = 0", userId);
    }
}
