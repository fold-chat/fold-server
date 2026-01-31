package chat.kith.db;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class MessageRepository {

    private static final TimeBasedEpochGenerator UUID_GEN = Generators.timeBasedEpochGenerator();

    @Inject
    DatabaseService db;

    public static String newId() {
        return UUID_GEN.generate().toString();
    }

    public void create(String id, String channelId, String authorId, String content) {
        db.execute(
                "INSERT INTO message (id, channel_id, author_id, content) VALUES (?, ?, ?, ?)",
                id, channelId, authorId, content
        );
    }

    public void create(String id, String channelId, String authorId, String content, String threadId) {
        if (threadId == null) {
            create(id, channelId, authorId, content);
            return;
        }
        db.execute(
                "INSERT INTO message (id, channel_id, author_id, content, thread_id) VALUES (?, ?, ?, ?, ?)",
                id, channelId, authorId, content, threadId
        );
    }

    public void create(DatabaseService.TxContext tx, String id, String channelId, String authorId, String content, String threadId) {
        if (threadId == null) {
            tx.execute("INSERT INTO message (id, channel_id, author_id, content) VALUES (?, ?, ?, ?)",
                    id, channelId, authorId, content);
        } else {
            tx.execute("INSERT INTO message (id, channel_id, author_id, content, thread_id) VALUES (?, ?, ?, ?, ?)",
                    id, channelId, authorId, content, threadId);
        }
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM message WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Map<String, Object>> findByIdWithAuthor(String id) {
        var rows = db.query("""
                SELECT m.*, u.username AS author_username, u.display_name AS author_display_name, u.avatar_url AS author_avatar_url
                FROM message m JOIN user u ON m.author_id = u.id
                WHERE m.id = ?""", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /**
     * Paginate messages in a channel. UUIDv7 IDs are lexicographically time-sorted.
     * @param before cursor — return messages older than this ID
     * @param after cursor — return messages newer than this ID
     * @param limit max results (default 50, max 200)
     */
    public List<Map<String, Object>> paginate(String channelId, String before, String after, int limit) {
        if (before != null) {
            return db.query(
                    "SELECT * FROM message WHERE channel_id = ? AND thread_id IS NULL AND id < ? ORDER BY id DESC LIMIT ?",
                    channelId, before, limit
            );
        } else if (after != null) {
            return db.query(
                    "SELECT * FROM (SELECT * FROM message WHERE channel_id = ? AND thread_id IS NULL AND id > ? ORDER BY id ASC LIMIT ?) sub ORDER BY id DESC",
                    channelId, after, limit
            );
        } else {
            return db.query(
                    "SELECT * FROM message WHERE channel_id = ? AND thread_id IS NULL ORDER BY id DESC LIMIT ?",
                    channelId, limit
            );
        }
    }

    public long updateContent(String id, String content) {
        return db.execute(
                "UPDATE message SET content = ?, edited_at = datetime('now') WHERE id = ?",
                content, id
        );
    }

    public long delete(String id) {
        return db.execute("DELETE FROM message WHERE id = ?", id);
    }

    /** Get message with author info for API responses — excludes threaded messages */
    public List<Map<String, Object>> paginateWithAuthor(String channelId, String before, String after, int limit) {
        String baseQuery = """
                SELECT m.*, u.username AS author_username, u.display_name AS author_display_name, u.avatar_url AS author_avatar_url
                FROM message m
                JOIN user u ON m.author_id = u.id
                WHERE m.channel_id = ? AND m.thread_id IS NULL""";

        if (before != null) {
            return db.query(baseQuery + " AND m.id < ? ORDER BY m.id DESC LIMIT ?",
                    channelId, before, limit);
        } else if (after != null) {
            return db.query(
                    "SELECT * FROM (" + baseQuery + " AND m.id > ? ORDER BY m.id ASC LIMIT ?) sub ORDER BY id DESC",
                    channelId, after, limit);
        } else {
            return db.query(baseQuery + " ORDER BY m.id DESC LIMIT ?",
                    channelId, limit);
        }
    }

    /** Paginate messages within a thread */
    public List<Map<String, Object>> paginateThreadMessages(String threadId, String before, String after, int limit) {
        String baseQuery = """
                SELECT m.*, u.username AS author_username, u.display_name AS author_display_name, u.avatar_url AS author_avatar_url
                FROM message m
                JOIN user u ON m.author_id = u.id
                WHERE m.thread_id = ?""";

        if (before != null) {
            return db.query(baseQuery + " AND m.id < ? ORDER BY m.id DESC LIMIT ?",
                    threadId, before, limit);
        } else if (after != null) {
            return db.query(
                    "SELECT * FROM (" + baseQuery + " AND m.id > ? ORDER BY m.id ASC LIMIT ?) sub ORDER BY id DESC",
                    threadId, after, limit);
        } else {
            return db.query(baseQuery + " ORDER BY m.id DESC LIMIT ?",
                    threadId, limit);
        }
    }

    /**
     * Get messages around a target message — limit before and after.
     * Used for search scroll-to: GET /channels/{channelId}/messages?around=messageId
     */
    public List<Map<String, Object>> paginateAround(String channelId, String messageId, int limit) {
        String baseSelect = """
                SELECT m.*, u.username AS author_username, u.display_name AS author_display_name, u.avatar_url AS author_avatar_url
                FROM message m
                JOIN user u ON m.author_id = u.id""";

        var before = db.query(
                baseSelect + " WHERE m.channel_id = ? AND m.thread_id IS NULL AND m.id < ? ORDER BY m.id DESC LIMIT ?",
                channelId, messageId, limit);
        var target = db.query(
                baseSelect + " WHERE m.id = ?", messageId);
        var after = db.query(
                "SELECT * FROM (" + baseSelect + " WHERE m.channel_id = ? AND m.thread_id IS NULL AND m.id > ? ORDER BY m.id ASC LIMIT ?) sub ORDER BY id DESC",
                channelId, messageId, limit);

        var result = new java.util.ArrayList<>(after);
        result.addAll(target);
        result.addAll(before);
        return result;
    }
}
