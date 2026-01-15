package chat.fray.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class UserRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String username, String displayName, String passwordHash) {
        db.execute(
                "INSERT INTO user (id, username, display_name, password_hash) VALUES (?, ?, ?, ?)",
                id, username, displayName, passwordHash
        );
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query(
                "SELECT * FROM user WHERE id = ? AND deleted_at IS NULL", id
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Map<String, Object>> findByUsername(String username) {
        var rows = db.query(
                "SELECT * FROM user WHERE username = ? COLLATE NOCASE AND deleted_at IS NULL", username
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public boolean existsByUsername(String username) {
        var rows = db.query(
                "SELECT 1 FROM user WHERE username = ? COLLATE NOCASE AND deleted_at IS NULL", username
        );
        return !rows.isEmpty();
    }

    public long countUsers() {
        var rows = db.query("SELECT COUNT(*) AS cnt FROM user WHERE deleted_at IS NULL");
        return (Long) rows.getFirst().get("cnt");
    }

    public void updateProfile(String id, String displayName, String bio, String statusPreference, String statusText, String avatarUrl) {
        db.execute(
                "UPDATE user SET display_name = ?, bio = ?, status_preference = ?, status_text = ?, avatar_url = ? WHERE id = ?",
                displayName, bio, statusPreference, statusText, avatarUrl, id
        );
    }

    public void updatePasswordHash(String id, String passwordHash) {
        db.execute("UPDATE user SET password_hash = ? WHERE id = ?", passwordHash, id);
    }

    public void incrementFailedLogin(String id) {
        db.execute("UPDATE user SET failed_login_count = failed_login_count + 1 WHERE id = ?", id);
    }

    public void lockUser(String id, String lockedUntil) {
        db.execute("UPDATE user SET locked_until = ? WHERE id = ?", lockedUntil, id);
    }

    public void resetFailedLogin(String id) {
        db.execute("UPDATE user SET failed_login_count = 0, locked_until = NULL WHERE id = ?", id);
    }

    public void updateLastSeen(String id) {
        db.execute("UPDATE user SET last_seen_at = datetime('now') WHERE id = ?", id);
    }

    public void softDelete(String id) {
        db.execute("UPDATE user SET deleted_at = datetime('now') WHERE id = ?", id);
    }

    public List<Map<String, Object>> listAll() {
        return db.query("SELECT * FROM user WHERE deleted_at IS NULL ORDER BY created_at");
    }

    public void assignRole(String userId, String roleId) {
        db.execute("INSERT OR IGNORE INTO user_role (user_id, role_id) VALUES (?, ?)", userId, roleId);
    }

    public List<Map<String, Object>> getUserRoles(String userId) {
        return db.query(
                "SELECT r.* FROM role r JOIN user_role ur ON r.id = ur.role_id WHERE ur.user_id = ?",
                userId
        );
    }

    /** List all members with their roles as JSON array of role objects */
    public List<Map<String, Object>> listMembers() {
        return db.query("""
                SELECT u.id, u.username, u.display_name, u.avatar_url, u.status_preference,
                       u.status_text, u.bio, u.created_at, u.last_seen_at,
                       JSON_GROUP_ARRAY(JSON_OBJECT('id', r.id, 'name', r.name, 'color', r.color)) AS roles
                FROM user u
                LEFT JOIN user_role ur ON u.id = ur.user_id
                LEFT JOIN role r ON ur.role_id = r.id
                WHERE u.deleted_at IS NULL
                GROUP BY u.id
                ORDER BY u.created_at
                """);
    }
}
