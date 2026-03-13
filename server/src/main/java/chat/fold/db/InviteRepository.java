package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class InviteRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String code, String creatorId, String description, Long maxUses, String expiresAt) {
        db.execute(
                "INSERT INTO invite (id, code, creator_id, description, max_uses, expires_at) VALUES (?, ?, ?, ?, ?, ?)",
                id, code, creatorId, description, maxUses, expiresAt
        );
    }

    public Optional<Map<String, Object>> findByCode(String code) {
        var rows = db.query("SELECT * FROM invite WHERE code = ?", code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** Find valid invite: not expired, not exhausted, not revoked */
    public Optional<Map<String, Object>> findValidByCode(String code) {
        var rows = db.query("""
                SELECT * FROM invite WHERE code = ?
                AND revoked_at IS NULL
                AND (expires_at IS NULL OR expires_at > datetime('now'))
                AND (max_uses IS NULL OR use_count < max_uses)
                """, code);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void incrementUseCount(String code) {
        db.execute("UPDATE invite SET use_count = use_count + 1 WHERE code = ?", code);
    }

    public void revoke(String code) {
        db.execute("UPDATE invite SET revoked_at = datetime('now') WHERE code = ?", code);
    }

    public void reinstate(String code) {
        db.execute("UPDATE invite SET revoked_at = NULL WHERE code = ?", code);
    }

    public void delete(String code) {
        db.execute("DELETE FROM invite WHERE code = ?", code);
    }

    public List<Map<String, Object>> listAll() {
        return db.query("""
                SELECT * FROM invite
                WHERE (expires_at IS NULL OR expires_at > datetime('now'))
                ORDER BY created_at DESC
                """);
    }
}
