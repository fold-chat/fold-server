package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;

@ApplicationScoped
public class DmRepository {

    @Inject DatabaseService db;

    /**
     * Find existing 1-on-1 DM or create one. Returns map with "channel_id" and "created" (boolean).
     * Wrapped in transaction to prevent race conditions.
     */
    public Map<String, Object> findOrCreateOneOnOne(String userId1, String userId2) {
        return db.transaction(tx -> {
            // Check for existing 1-on-1 between these two users
            var existing = tx.query("""
                    SELECT dc1.channel_id FROM dm_conversation dc1
                    JOIN dm_conversation dc2 ON dc1.channel_id = dc2.channel_id
                    WHERE dc1.user_id = ? AND dc2.user_id = ?
                    AND (SELECT COUNT(*) FROM dm_conversation dc3 WHERE dc3.channel_id = dc1.channel_id) = 2
                    """, userId1, userId2);

            if (!existing.isEmpty()) {
                return Map.of("channel_id", existing.getFirst().get("channel_id"), "created", false);
            }

            // Create new DM channel + participant rows
            String channelId = MessageRepository.newId();
            tx.execute("INSERT INTO channel (id, name, type, position) VALUES (?, '', 'DM', -1)", channelId);
            tx.execute("INSERT INTO dm_conversation (channel_id, user_id) VALUES (?, ?)", channelId, userId1);
            tx.execute("INSERT INTO dm_conversation (channel_id, user_id) VALUES (?, ?)", channelId, userId2);
            return Map.<String, Object>of("channel_id", channelId, "created", true);
        });
    }

    /**
     * Find DM conversations for a user with participant info and block status.
     * Returns list ordered by last_activity_at DESC.
     */
    public List<Map<String, Object>> findConversationsForUser(String userId) {
        return db.query("""
                SELECT dc.channel_id,
                       MAX(dc.last_activity_at) AS last_activity_at,
                       CASE WHEN EXISTS (
                           SELECT 1 FROM dm_block b
                           JOIN dm_conversation dc2 ON dc2.channel_id = dc.channel_id AND dc2.user_id != ?
                           WHERE (b.blocker_id = ? AND b.blocked_id = dc2.user_id)
                              OR (b.blocker_id = dc2.user_id AND b.blocked_id = ?)
                       ) THEN 1 ELSE 0 END AS is_blocked
                FROM dm_conversation dc
                WHERE dc.channel_id IN (SELECT channel_id FROM dm_conversation WHERE user_id = ?)
                GROUP BY dc.channel_id
                ORDER BY last_activity_at DESC
                """, userId, userId, userId, userId);
    }

    /** Get participant user IDs for a DM channel */
    public Set<String> findParticipants(String channelId) {
        var rows = db.query("SELECT user_id FROM dm_conversation WHERE channel_id = ?", channelId);
        var result = new HashSet<String>();
        for (var row : rows) result.add((String) row.get("user_id"));
        return result;
    }

    /** Get participant details (id, username, display_name, avatar_url) for a DM channel */
    public List<Map<String, Object>> findParticipantDetails(String channelId) {
        return db.query("""
                SELECT u.id, u.username, u.display_name, u.avatar_url
                FROM dm_conversation dc
                JOIN user u ON u.id = dc.user_id
                WHERE dc.channel_id = ? AND u.deleted_at IS NULL
                """, channelId);
    }

    public boolean isParticipant(String channelId, String userId) {
        var rows = db.query(
                "SELECT 1 FROM dm_conversation WHERE channel_id = ? AND user_id = ?",
                channelId, userId);
        return !rows.isEmpty();
    }

    public void updateLastActivity(String channelId) {
        db.execute(
                "UPDATE dm_conversation SET last_activity_at = datetime('now') WHERE channel_id = ?",
                channelId);
    }

    public boolean isDmChannel(String channelId) {
        var rows = db.query("SELECT 1 FROM channel WHERE id = ? AND type = 'DM'", channelId);
        return !rows.isEmpty();
    }
}
