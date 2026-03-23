package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class VoiceStateRepository {

    @Inject
    DatabaseService db;

    /** List voice states for a channel, joined with user info */
    public List<Map<String, Object>> findByChannel(String channelId) {
        return db.query("""
                SELECT vs.*, u.username, u.display_name, u.avatar_url
                FROM voice_state vs
                JOIN user u ON u.id = vs.user_id
                WHERE vs.channel_id = ?
                ORDER BY vs.joined_at
                """, channelId);
    }

    /** Bulk: list voice states for multiple channels in a single query */
    public List<Map<String, Object>> findByChannels(java.util.Collection<String> channelIds) {
        if (channelIds.isEmpty()) return java.util.List.of();
        var placeholders = String.join(",", channelIds.stream().map(id -> "?").toList());
        return db.query(
                "SELECT vs.*, u.username, u.display_name, u.avatar_url "
                + "FROM voice_state vs JOIN user u ON u.id = vs.user_id "
                + "WHERE vs.channel_id IN (" + placeholders + ") "
                + "ORDER BY vs.joined_at",
                channelIds.toArray());
    }

    /** Get the current voice state for a user (0-1 rows — one channel at a time) */
    public Optional<Map<String, Object>> findByUser(String userId) {
        var rows = db.query("SELECT * FROM voice_state WHERE user_id = ?", userId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Join or update voice state */
    public void upsert(String userId, String channelId, int selfMute, int selfDeaf) {
        db.execute("""
                INSERT INTO voice_state (user_id, channel_id, self_mute, self_deaf, joined_at)
                VALUES (?, ?, ?, ?, datetime('now'))
                ON CONFLICT (user_id, channel_id) DO UPDATE SET
                    self_mute = excluded.self_mute,
                    self_deaf = excluded.self_deaf
                """, userId, channelId, selfMute, selfDeaf);
    }

    /** Leave voice — delete all voice state rows for a user */
    public void delete(String userId) {
        db.execute("DELETE FROM voice_state WHERE user_id = ?", userId);
    }

    /** Cleanup when a channel is deleted */
    public void deleteByChannel(String channelId) {
        db.execute("DELETE FROM voice_state WHERE channel_id = ?", channelId);
    }

    public void setServerMute(String userId, String channelId, boolean muted) {
        db.execute("UPDATE voice_state SET server_mute = ? WHERE user_id = ? AND channel_id = ?",
                muted ? 1 : 0, userId, channelId);
    }

    public void setServerDeaf(String userId, String channelId, boolean deaf) {
        db.execute("UPDATE voice_state SET server_deaf = ? WHERE user_id = ? AND channel_id = ?",
                deaf ? 1 : 0, userId, channelId);
    }

    /** Clear all voice states — used for startup reconciliation */
    public void clearAll() {
        db.execute("DELETE FROM voice_state");
    }

    /** Count active voice connections */
    public long countAll() {
        var rows = db.query("SELECT COUNT(*) AS cnt FROM voice_state");
        return (Long) rows.getFirst().get("cnt");
    }
}
