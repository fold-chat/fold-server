package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class RoleRepository {

    @Inject
    DatabaseService db;

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM role WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> findAll() {
        return db.query("SELECT * FROM role ORDER BY position");
    }

    public void create(String id, String name, long permissions, int position, String color, boolean isDefault) {
        db.execute(
                "INSERT INTO role (id, name, permissions, position, color, is_default) VALUES (?, ?, ?, ?, ?, ?)",
                id, name, permissions, position, color, isDefault ? 1L : 0L
        );
    }

    public void update(String id, String name, long permissions, int position, String color) {
        db.execute(
                "UPDATE role SET name = ?, permissions = ?, position = ?, color = ? WHERE id = ?",
                name, permissions, position, color, id
        );
    }

    public void delete(String id) {
        db.execute("DELETE FROM role WHERE id = ?", id);
    }

    public Optional<Map<String, Object>> findDefaultRole() {
        var rows = db.query("SELECT * FROM role WHERE is_default = 1 LIMIT 1");
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void setDefault(String roleId) {
        db.transactionVoid(tx -> {
            tx.execute("UPDATE role SET is_default = 0 WHERE is_default = 1");
            tx.execute("UPDATE role SET is_default = 1 WHERE id = ?", roleId);
        });
    }

    public int nextPosition() {
        var rows = db.query("SELECT COALESCE(MAX(position), 0) + 1 AS next_pos FROM role");
        return ((Long) rows.getFirst().get("next_pos")).intValue();
    }

    public void batchUpdatePositions(List<IdPosition> items) {
        db.transactionVoid(tx -> {
            // Negative offsets to avoid UNIQUE constraint conflicts mid-transaction
            for (int i = 0; i < items.size(); i++) {
                tx.execute("UPDATE role SET position = ? WHERE id = ?", -(i + 1), items.get(i).id());
            }
            for (var item : items) {
                tx.execute("UPDATE role SET position = ? WHERE id = ?", item.position(), item.id());
            }
        });
    }

    public record IdPosition(String id, int position) {}

    public boolean existsByPosition(int position, String excludeId) {
        var rows = db.query(
                "SELECT 1 FROM role WHERE position = ? AND id != ?",
                position, excludeId
        );
        return !rows.isEmpty();
    }

    public long countUsersWithRole(String roleId) {
        var rows = db.query("SELECT COUNT(*) AS cnt FROM user_role WHERE role_id = ?", roleId);
        return (Long) rows.getFirst().get("cnt");
    }

    public List<String> findUserIdsWithRole(String roleId) {
        var rows = db.query("SELECT user_id FROM user_role WHERE role_id = ?", roleId);
        return rows.stream().map(r -> (String) r.get("user_id")).toList();
    }

    // --- User role assignment ---

    public List<String> findUserRoleIds(String userId) {
        var rows = db.query(
                "SELECT role_id FROM user_role WHERE user_id = ?", userId
        );
        return rows.stream().map(r -> (String) r.get("role_id")).toList();
    }

    public void assignRole(String userId, String roleId) {
        db.execute("INSERT OR IGNORE INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }

    public void removeRole(String userId, String roleId) {
        db.execute("DELETE FROM user_role WHERE user_id = ? AND role_id = ?", userId, roleId);
    }

    // --- Channel permission overrides ---

    public List<Map<String, Object>> findChannelOverrides(String channelId) {
        return db.query(
                "SELECT * FROM channel_permission_override WHERE channel_id = ? ORDER BY role_id",
                channelId
        );
    }

    public Optional<Map<String, Object>> findRoleOverride(String channelId, String roleId) {
        var rows = db.query(
                "SELECT * FROM channel_permission_override WHERE channel_id = ? AND role_id = ?",
                channelId, roleId
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void upsertOverride(String id, String channelId, String roleId, long allow, long deny) {
        db.execute("""
                INSERT INTO channel_permission_override (id, channel_id, role_id, allow, deny)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(channel_id, role_id) DO UPDATE SET allow = ?, deny = ?
                """,
                id, channelId, roleId, allow, deny, allow, deny
        );
    }

    public void deleteOverride(String channelId, String roleId) {
        db.execute(
                "DELETE FROM channel_permission_override WHERE channel_id = ? AND role_id = ?",
                channelId, roleId
        );
    }

    /** Get all channel overrides for a set of role IDs (for permission resolution) */
    public List<Map<String, Object>> findOverridesForRoles(String channelId, List<String> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        var placeholders = String.join(",", roleIds.stream().map(id -> "?").toList());
        var params = new Object[roleIds.size() + 1];
        params[0] = channelId;
        for (int i = 0; i < roleIds.size(); i++) params[i + 1] = roleIds.get(i);
        return db.query(
                "SELECT * FROM channel_permission_override WHERE channel_id = ? AND role_id IN (" + placeholders + ")",
                params
        );
    }

    /** Bulk: load all overrides for given channels + roles in one query. Returns flat list with channel_id + role_id on each row. */
    public List<Map<String, Object>> findOverridesForChannelsAndRoles(java.util.Set<String> channelIds, List<String> roleIds) {
        if (channelIds.isEmpty() || roleIds.isEmpty()) return List.of();
        var chPlaceholders = String.join(",", channelIds.stream().map(id -> "?").toList());
        var rolePlaceholders = String.join(",", roleIds.stream().map(id -> "?").toList());
        var params = new Object[channelIds.size() + roleIds.size()];
        int i = 0;
        for (var id : channelIds) params[i++] = id;
        for (var id : roleIds) params[i++] = id;
        return db.query(
                "SELECT * FROM channel_permission_override WHERE channel_id IN (" + chPlaceholders + ") AND role_id IN (" + rolePlaceholders + ")",
                params
        );
    }
}
