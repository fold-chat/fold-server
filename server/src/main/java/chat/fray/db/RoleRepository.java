package chat.fray.db;

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
}
