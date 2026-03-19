package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class DmBlockRepository {

    @Inject DatabaseService db;

    public void block(String blockerId, String blockedId) {
        db.execute(
                "INSERT OR IGNORE INTO dm_block (blocker_id, blocked_id) VALUES (?, ?)",
                blockerId, blockedId);
    }

    public void unblock(String blockerId, String blockedId) {
        db.execute(
                "DELETE FROM dm_block WHERE blocker_id = ? AND blocked_id = ?",
                blockerId, blockedId);
    }

    public boolean isBlocked(String blockerId, String blockedId) {
        var rows = db.query(
                "SELECT 1 FROM dm_block WHERE blocker_id = ? AND blocked_id = ?",
                blockerId, blockedId);
        return !rows.isEmpty();
    }

    /** True if either user has blocked the other */
    public boolean isBlockedEither(String userId1, String userId2) {
        var rows = db.query("""
                SELECT 1 FROM dm_block
                WHERE (blocker_id = ? AND blocked_id = ?)
                   OR (blocker_id = ? AND blocked_id = ?)
                LIMIT 1
                """, userId1, userId2, userId2, userId1);
        return !rows.isEmpty();
    }

    /** List of user IDs this user has blocked */
    public List<String> getBlockedIds(String userId) {
        var rows = db.query("SELECT blocked_id FROM dm_block WHERE blocker_id = ?", userId);
        var result = new ArrayList<String>();
        for (var row : rows) result.add((String) row.get("blocked_id"));
        return result;
    }
}
