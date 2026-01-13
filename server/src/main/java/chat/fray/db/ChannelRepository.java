package chat.fray.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ChannelRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String name, String type, String categoryId, String topic, String description, int position) {
        db.execute(
                "INSERT INTO channel (id, name, type, category_id, topic, description, position) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, name, type, categoryId, topic, description, position
        );
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM channel WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> listAll() {
        return db.query("SELECT * FROM channel ORDER BY position, created_at");
    }

    public void update(String id, String name, String topic, String description, String categoryId, Integer position) {
        db.execute(
                "UPDATE channel SET name = ?, topic = ?, description = ?, category_id = ?, position = ? WHERE id = ?",
                name, topic, description, categoryId, position, id
        );
    }

    public void delete(String id) {
        db.execute("DELETE FROM channel WHERE id = ?", id);
    }

    public int nextPosition() {
        var rows = db.query("SELECT COALESCE(MAX(position), -1) + 1 AS next_pos FROM channel");
        return ((Long) rows.getFirst().get("next_pos")).intValue();
    }

    /** Count messages in a channel after a given message ID (for unread counts) */
    public long countMessagesAfter(String channelId, String afterMessageId) {
        if (afterMessageId == null) {
            var rows = db.query("SELECT COUNT(*) AS cnt FROM message WHERE channel_id = ?", channelId);
            return (Long) rows.getFirst().get("cnt");
        }
        var rows = db.query(
                "SELECT COUNT(*) AS cnt FROM message WHERE channel_id = ? AND id > ?",
                channelId, afterMessageId
        );
        return (Long) rows.getFirst().get("cnt");
    }

    /** Get the latest message ID in a channel */
    public Optional<String> latestMessageId(String channelId) {
        var rows = db.query(
                "SELECT id FROM message WHERE channel_id = ? ORDER BY id DESC LIMIT 1",
                channelId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of((String) rows.getFirst().get("id"));
    }
}
