package chat.fray.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class SessionRepository {

    private static final int MAX_SESSIONS_PER_USER = 20;

    @Inject
    DatabaseService db;

    public void create(String id, String userId, String refreshTokenHash, String expiresAt, String userAgent) {
        // Evict oldest sessions if at limit
        evictOldest(userId);
        db.execute(
                "INSERT INTO session (id, user_id, refresh_token_hash, expires_at, user_agent) VALUES (?, ?, ?, ?, ?)",
                id, userId, refreshTokenHash, expiresAt, userAgent
        );
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM session WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Map<String, Object>> findByRefreshTokenHash(String hash) {
        var rows = db.query(
                "SELECT * FROM session WHERE refresh_token_hash = ? AND expires_at > datetime('now')",
                hash
        );
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void updateRefreshToken(String id, String newHash, String newExpiresAt) {
        db.execute(
                "UPDATE session SET refresh_token_hash = ?, expires_at = ?, last_used_at = datetime('now') WHERE id = ?",
                newHash, newExpiresAt, id
        );
    }

    public void delete(String id) {
        db.execute("DELETE FROM session WHERE id = ?", id);
    }

    public void deleteAllForUser(String userId) {
        db.execute("DELETE FROM session WHERE user_id = ?", userId);
    }

    public void deleteAllForUserExcept(String userId, String sessionId) {
        db.execute("DELETE FROM session WHERE user_id = ? AND id != ?", userId, sessionId);
    }

    public void deleteExpired() {
        db.execute("DELETE FROM session WHERE expires_at <= datetime('now')");
    }

    private void evictOldest(String userId) {
        var rows = db.query(
                "SELECT COUNT(*) AS cnt FROM session WHERE user_id = ?", userId
        );
        long count = (Long) rows.getFirst().get("cnt");
        if (count >= MAX_SESSIONS_PER_USER) {
            db.execute("""
                    DELETE FROM session WHERE id IN (
                        SELECT id FROM session WHERE user_id = ?
                        ORDER BY last_used_at ASC LIMIT ?
                    )
                    """, userId, (long) (count - MAX_SESSIONS_PER_USER + 1));
        }
    }
}
