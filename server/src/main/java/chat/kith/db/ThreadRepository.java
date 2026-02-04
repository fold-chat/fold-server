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

    /** Get thread with reply count, content preview, and last replier */
    public Optional<Map<String, Object>> findByIdWithMeta(String id) {
        var rows = db.query("""
                SELECT t.*,
                       u.username AS author_username, u.display_name AS author_display_name, u.avatar_url AS author_avatar_url,
                       CASE WHEN t.parent_message_id IS NULL
                            THEN MAX(0, (SELECT COUNT(*) FROM message m WHERE m.thread_id = t.id) - 1)
                            ELSE (SELECT COUNT(*) FROM message m WHERE m.thread_id = t.id)
                       END AS reply_count,
                       (SELECT SUBSTR(m2.content, 1, 300) FROM message m2 WHERE m2.thread_id = t.id ORDER BY m2.id ASC LIMIT 1) AS first_message_content,
                       (SELECT u2.username FROM message m3 JOIN user u2 ON m3.author_id = u2.id WHERE m3.thread_id = t.id ORDER BY m3.id DESC LIMIT 1) AS last_reply_username,
                       (SELECT u3.avatar_url FROM message m4 JOIN user u3 ON m4.author_id = u3.id WHERE m4.thread_id = t.id ORDER BY m4.id DESC LIMIT 1) AS last_reply_avatar_url,
                       (SELECT m5.created_at FROM message m5 WHERE m5.thread_id = t.id ORDER BY m5.id DESC LIMIT 1) AS last_reply_at
                FROM thread t
                JOIN user u ON t.author_id = u.id
                WHERE t.id = ?""", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Paginate threads in a channel, pinned first, then by last_activity_at desc */
    public List<Map<String, Object>> findByChannelId(String channelId, String before, int limit) {
        String baseQuery = """
                SELECT t.*,
                       u.username AS author_username, u.display_name AS author_display_name, u.avatar_url AS author_avatar_url,
                       CASE WHEN t.parent_message_id IS NULL
                            THEN MAX(0, (SELECT COUNT(*) FROM message m WHERE m.thread_id = t.id) - 1)
                            ELSE (SELECT COUNT(*) FROM message m WHERE m.thread_id = t.id)
                       END AS reply_count,
                       (SELECT SUBSTR(m2.content, 1, 300) FROM message m2 WHERE m2.thread_id = t.id ORDER BY m2.id ASC LIMIT 1) AS first_message_content,
                       (SELECT u2.username FROM message m3 JOIN user u2 ON m3.author_id = u2.id WHERE m3.thread_id = t.id ORDER BY m3.id DESC LIMIT 1) AS last_reply_username,
                       (SELECT u3.avatar_url FROM message m4 JOIN user u3 ON m4.author_id = u3.id WHERE m4.thread_id = t.id ORDER BY m4.id DESC LIMIT 1) AS last_reply_avatar_url,
                       (SELECT m5.created_at FROM message m5 WHERE m5.thread_id = t.id ORDER BY m5.id DESC LIMIT 1) AS last_reply_at
                FROM thread t
                JOIN user u ON t.author_id = u.id
                WHERE t.channel_id = ?""";

        if (before != null) {
            return db.query(baseQuery + " AND t.last_activity_at < ? ORDER BY t.pinned DESC, t.last_activity_at DESC LIMIT ?",
                    channelId, before, limit);
        }
        return db.query(baseQuery + " ORDER BY t.pinned DESC, t.last_activity_at DESC LIMIT ?",
                channelId, limit);
    }

    public Optional<Map<String, Object>> findByParentMessageId(String parentMessageId) {
        var rows = db.query("SELECT * FROM thread WHERE parent_message_id = ?", parentMessageId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public long update(String id, String title, Integer locked, Integer pinned) {
        var sets = new java.util.ArrayList<String>();
        var params = new java.util.ArrayList<Object>();
        if (title != null) { sets.add("title = ?"); params.add(title); }
        if (locked != null) { sets.add("locked = ?"); params.add(locked); }
        if (pinned != null) { sets.add("pinned = ?"); params.add(pinned); }
        if (sets.isEmpty()) return 0;
        params.add(id);
        return db.execute("UPDATE thread SET " + String.join(", ", sets) + " WHERE id = ?", params.toArray());
    }

    public long delete(String id) {
        return db.execute("DELETE FROM thread WHERE id = ?", id);
    }

    public void updateLastActivity(String threadId) {
        db.execute("UPDATE thread SET last_activity_at = datetime('now') WHERE id = ?", threadId);
    }
}
