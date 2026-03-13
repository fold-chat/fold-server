package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ReactionRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String messageId, String userId, String emoji) {
        db.execute(
                "INSERT OR IGNORE INTO reaction (id, message_id, user_id, emoji) VALUES (?, ?, ?, ?)",
                id, messageId, userId, emoji
        );
    }

    public long delete(String messageId, String userId, String emoji) {
        return db.execute(
                "DELETE FROM reaction WHERE message_id = ? AND user_id = ? AND emoji = ?",
                messageId, userId, emoji
        );
    }

    public List<Map<String, Object>> findByMessageId(String messageId) {
        return db.query("SELECT * FROM reaction WHERE message_id = ?", messageId);
    }

    /**
     * Batch load reactions for multiple messages. Returns messageId → list of reaction rows.
     */
    public Map<String, List<Map<String, Object>>> findByMessageIds(List<String> messageIds) {
        if (messageIds.isEmpty()) return Map.of();
        var placeholders = String.join(",", messageIds.stream().map(id -> "?").toList());
        var rows = db.query(
                "SELECT r.*, u.username FROM reaction r JOIN user u ON r.user_id = u.id WHERE r.message_id IN (" + placeholders + ")",
                messageIds.toArray()
        );
        var result = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (var row : rows) {
            var msgId = (String) row.get("message_id");
            result.computeIfAbsent(msgId, k -> new ArrayList<>()).add(row);
        }
        return result;
    }

    public int countUniqueEmojiForMessage(String messageId) {
        var rows = db.query(
                "SELECT COUNT(DISTINCT emoji) AS cnt FROM reaction WHERE message_id = ?",
                messageId
        );
        if (rows.isEmpty()) return 0;
        return ((Number) rows.getFirst().get("cnt")).intValue();
    }
}
