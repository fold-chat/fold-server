package chat.kith.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@ApplicationScoped
public class ThreadRepository {

    @Inject
    DatabaseService db;

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public void create(String id, String channelId, String parentMessageId, String title, String authorId) {
        db.execute("""
                INSERT INTO thread (id, channel_id, parent_message_id, title, author_id)
                VALUES (?, ?, ?, ?, ?)""",
                id, channelId, parentMessageId, title, authorId
        );
    }

    public void create(DatabaseService.TxContext tx, String id, String channelId, String parentMessageId, String title, String authorId) {
        tx.execute("""
                INSERT INTO thread (id, channel_id, parent_message_id, title, author_id)
                VALUES (?, ?, ?, ?, ?)""",
                id, channelId, parentMessageId, title, authorId
        );
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM thread WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Get thread with reply count and last message preview */
    public Optional<Map<String, Object>> findByIdWithMeta(String id) {
        var rows = db.query("""
                SELECT t.*,
                       u.username AS author_username, u.display_name AS author_display_name, u.avatar_url AS author_avatar_url,
                       (SELECT COUNT(*) FROM message m WHERE m.thread_id = t.id) AS reply_count
                FROM thread t
                JOIN user u ON t.author_id = u.id
                WHERE t.id = ?""", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Paginate threads in a channel by last_activity_at desc */
    public List<Map<String, Object>> findByChannelId(String channelId, String before, int limit) {
        String baseQuery = """
                SELECT t.*,
                       u.username AS author_username, u.display_name AS author_display_name, u.avatar_url AS author_avatar_url,
                       (SELECT COUNT(*) FROM message m WHERE m.thread_id = t.id) AS reply_count
                FROM thread t
                JOIN user u ON t.author_id = u.id
                WHERE t.channel_id = ?""";

        if (before != null) {
            return db.query(baseQuery + " AND t.last_activity_at < ? ORDER BY t.last_activity_at DESC LIMIT ?",
                    channelId, before, limit);
        }
        return db.query(baseQuery + " ORDER BY t.last_activity_at DESC LIMIT ?",
                channelId, limit);
    }

    public Optional<Map<String, Object>> findByParentMessageId(String parentMessageId) {
        var rows = db.query("SELECT * FROM thread WHERE parent_message_id = ?", parentMessageId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public long update(String id, String title, Integer locked) {
        if (title != null && locked != null) {
            return db.execute("UPDATE thread SET title = ?, locked = ? WHERE id = ?", title, locked, id);
        } else if (title != null) {
            return db.execute("UPDATE thread SET title = ? WHERE id = ?", title, id);
        } else if (locked != null) {
            return db.execute("UPDATE thread SET locked = ? WHERE id = ?", locked, id);
        }
        return 0;
    }

    public long delete(String id) {
        return db.execute("DELETE FROM thread WHERE id = ?", id);
    }

    public void updateLastActivity(String threadId) {
        db.execute("UPDATE thread SET last_activity_at = datetime('now') WHERE id = ?", threadId);
    }
}
