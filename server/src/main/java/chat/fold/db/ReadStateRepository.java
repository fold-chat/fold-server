package chat.fold.db;

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
                INSERT INTO channel_read_state (user_id, channel_id, last_read_message_id, mention_count, updated_at)
                VALUES (?, ?, ?, 0, datetime('now'))
                ON CONFLICT (user_id, channel_id) DO UPDATE SET
                    last_read_message_id = excluded.last_read_message_id,
                    mention_count = 0,
                    updated_at = datetime('now')
                """,
                userId, channelId, lastReadMessageId
        );
    }

    /** Increment mention_count for a user's channel read state. Creates row if missing. */
    public void incrementMentionCount(String userId, String channelId) {
        db.execute("""
                INSERT INTO channel_read_state (user_id, channel_id, mention_count, updated_at)
                VALUES (?, ?, 1, datetime('now'))
                ON CONFLICT (user_id, channel_id) DO UPDATE SET
                    mention_count = channel_read_state.mention_count + 1,
                    updated_at = datetime('now')
                """,
                userId, channelId
        );
    }

    /** Initialize read states for all channels so a new user starts with 0 unread. */
    public void initializeForUser(String userId) {
        db.execute("""
                INSERT OR IGNORE INTO channel_read_state (user_id, channel_id, last_read_message_id, mention_count, updated_at)
                SELECT ?, c.id,
                    (SELECT m.id FROM message m WHERE m.channel_id = c.id AND m.thread_id IS NULL ORDER BY m.id DESC LIMIT 1),
                    0, datetime('now')
                FROM channel c
                """,
                userId
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
     * Excludes threaded messages — those are tracked separately via thread_read_state.
     * Includes mention_count from read state.
     */
    public List<Map<String, Object>> unreadCounts(String userId) {
        return db.query("""
                SELECT c.id AS channel_id,
                       COUNT(m.id) AS unread_count,
                       COALESCE(rs.mention_count, 0) AS mention_count
                FROM channel c
                LEFT JOIN channel_read_state rs ON rs.channel_id = c.id AND rs.user_id = ?
                LEFT JOIN message m ON m.channel_id = c.id AND m.thread_id IS NULL AND (rs.last_read_message_id IS NULL OR m.id > rs.last_read_message_id)
                GROUP BY c.id
                """,
                userId
        );
    }

    /**
     * Get unread counts only for the given channel IDs (avoids scanning all channels).
     */
    public List<Map<String, Object>> unreadCountsForChannels(String userId, java.util.Set<String> channelIds) {
        if (channelIds.isEmpty()) return List.of();
        var placeholders = String.join(",", channelIds.stream().map(id -> "?").toList());
        var params = new Object[channelIds.size() + 1];
        params[0] = userId;
        int i = 1;
        for (var id : channelIds) params[i++] = id;
        return db.query(
                "SELECT rs.channel_id, COUNT(m.id) AS unread_count, COALESCE(rs.mention_count, 0) AS mention_count "
                + "FROM channel_read_state rs "
                + "LEFT JOIN message m ON m.channel_id = rs.channel_id AND m.thread_id IS NULL AND m.id > rs.last_read_message_id "
                + "WHERE rs.user_id = ? AND rs.channel_id IN (" + placeholders + ") "
                + "GROUP BY rs.channel_id",
                params
        );
    }

    // --- Thread read state ---

    public void upsertThread(String userId, String threadId, String lastReadMessageId) {
        db.execute("""
                INSERT INTO thread_read_state (user_id, thread_id, last_read_message_id, updated_at)
                VALUES (?, ?, ?, datetime('now'))
                ON CONFLICT (user_id, thread_id) DO UPDATE SET
                    last_read_message_id = excluded.last_read_message_id,
                    updated_at = datetime('now')
                """,
                userId, threadId, lastReadMessageId
        );
    }

    public Optional<Map<String, Object>> findThreadReadState(String userId, String threadId) {
        var rows = db.query(
                "SELECT * FROM thread_read_state WHERE user_id = ? AND thread_id = ?",
                userId, threadId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Get unread threads for a user in a channel, capped at limit.
     * Returns threads that have messages newer than the user's last read, ordered by most recent activity.
     */
    public List<Map<String, Object>> unreadThreadsForUserChannel(String userId, String channelId, int limit) {
        return db.query("""
                SELECT t.id AS thread_id, t.channel_id, t.last_activity_at,
                       trs.last_read_message_id,
                       COUNT(m.id) AS unread_count
                FROM thread t
                LEFT JOIN thread_read_state trs ON trs.thread_id = t.id AND trs.user_id = ?
                LEFT JOIN message m ON m.thread_id = t.id AND (trs.last_read_message_id IS NULL OR m.id > trs.last_read_message_id)
                WHERE t.channel_id = ?
                GROUP BY t.id
                HAVING COUNT(m.id) > 0
                ORDER BY t.last_activity_at DESC
                LIMIT ?
                """,
                userId, channelId, limit
        );
    }

    /**
     * Bulk: get unread threads across multiple channels in a single query.
     * Returns up to limitPerChannel unread threads per channel, ordered by most recent activity.
     */
    public List<Map<String, Object>> unreadThreadsForUserChannels(String userId, java.util.Set<String> channelIds, int limitPerChannel) {
        if (channelIds.isEmpty()) return List.of();
        var placeholders = String.join(",", channelIds.stream().map(id -> "?").toList());
        var params = new Object[channelIds.size() + 2];
        params[0] = userId;
        int i = 1;
        for (var id : channelIds) params[i++] = id;
        params[i] = limitPerChannel;
        return db.query(
                "SELECT ranked.* FROM ("
                + "SELECT t.id AS thread_id, t.channel_id, t.last_activity_at, "
                + "trs.last_read_message_id, COUNT(m.id) AS unread_count, "
                + "ROW_NUMBER() OVER (PARTITION BY t.channel_id ORDER BY t.last_activity_at DESC) AS rn "
                + "FROM thread t "
                + "LEFT JOIN thread_read_state trs ON trs.thread_id = t.id AND trs.user_id = ? "
                + "LEFT JOIN message m ON m.thread_id = t.id AND (trs.last_read_message_id IS NULL OR m.id > trs.last_read_message_id) "
                + "WHERE t.channel_id IN (" + placeholders + ") "
                + "GROUP BY t.id HAVING COUNT(m.id) > 0"
                + ") ranked WHERE ranked.rn <= ?",
                params
        );
    }

    /** Get all thread read states for a user (for HELLO payload) */
    public List<Map<String, Object>> findAllThreadReadStatesForUser(String userId) {
        return db.query("SELECT * FROM thread_read_state WHERE user_id = ?", userId);
    }
}
