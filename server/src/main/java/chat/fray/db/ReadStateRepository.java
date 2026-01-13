package chat.fray.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ReadStateRepository {

    @Inject
    DatabaseService db;

    public void upsert(String userId, String channelId, String lastReadMessageId) {
        db.execute("""
                INSERT INTO channel_read_state (user_id, channel_id, last_read_message_id, updated_at)
                VALUES (?, ?, ?, datetime('now'))
                ON CONFLICT (user_id, channel_id) DO UPDATE SET
                    last_read_message_id = excluded.last_read_message_id,
                    updated_at = datetime('now')
                """,
                userId, channelId, lastReadMessageId
        );
    }

    public Optional<Map<String, Object>> find(String userId, String channelId) {
        var rows = db.query(
                "SELECT * FROM channel_read_state WHERE user_id = ? AND channel_id = ?",
                userId, channelId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Get all read states for a user (for HELLO payload + unread calc) */
    public List<Map<String, Object>> findAllForUser(String userId) {
        return db.query("SELECT * FROM channel_read_state WHERE user_id = ?", userId);
    }

    /**
     * Get unread counts per channel for a user.
     * Returns rows with channel_id and unread_count.
     */
    public List<Map<String, Object>> unreadCounts(String userId) {
        return db.query("""
                SELECT c.id AS channel_id,
                       COUNT(m.id) AS unread_count
                FROM channel c
                LEFT JOIN channel_read_state rs ON rs.channel_id = c.id AND rs.user_id = ?
                LEFT JOIN message m ON m.channel_id = c.id AND (rs.last_read_message_id IS NULL OR m.id > rs.last_read_message_id)
                GROUP BY c.id
                """,
                userId
        );
    }
}
