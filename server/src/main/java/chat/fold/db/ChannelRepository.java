package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ChannelRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String name, String type, String categoryId, String topic, String description, int position, String icon, String iconUrl) {
        db.execute(
                "INSERT INTO channel (id, name, type, category_id, topic, description, position, icon, icon_url) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, name, type, categoryId, topic, description, position, icon, iconUrl
        );
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM channel WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> listAll() {
        return db.query("SELECT * FROM channel ORDER BY position, created_at");
    }

    /** List only server channels (excludes DM type) */
    public List<Map<String, Object>> listServerChannels() {
        return db.query("SELECT * FROM channel WHERE type != 'DM' ORDER BY position, created_at");
    }

    public void update(String id, String name, String topic, String description, String categoryId, Integer position, String icon, String iconUrl) {
        db.execute(
                "UPDATE channel SET name = ?, topic = ?, description = ?, category_id = ?, position = ?, icon = ?, icon_url = ? WHERE id = ?",
                name, topic, description, categoryId, position, icon, iconUrl, id
        );
    }

    public void delete(String id) {
        db.execute("DELETE FROM channel WHERE id = ?", id);
    }

    public void archive(String id) {
        db.execute("UPDATE channel SET archived_at = datetime('now') WHERE id = ?", id);
    }

    public void unarchive(String id) {
        db.execute("UPDATE channel SET archived_at = NULL WHERE id = ?", id);
    }

    public List<Map<String, Object>> findByCategoryId(String categoryId) {
        return db.query("SELECT * FROM channel WHERE category_id = ?", categoryId);
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

    public void batchUpdatePositions(List<IdPositionCategory> items) {
        db.transactionVoid(tx -> {
            for (var item : items) {
                tx.execute("UPDATE channel SET position = ?, category_id = ? WHERE id = ?",
                        item.position(), item.categoryId(), item.id());
            }
        });
    }

    public record IdPositionCategory(String id, int position, String categoryId) {}
}
