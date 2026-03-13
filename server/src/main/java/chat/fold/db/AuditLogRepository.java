package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class AuditLogRepository {
    @Inject
    DatabaseService db;

    public void create(String actorId, String action, String targetType, String targetId, String details) {
        db.execute("""
            INSERT INTO audit_log (id, actor_id, action, target_type, target_id, details)
            VALUES (?, ?, ?, ?, ?, ?)
            """,
            UUID.randomUUID().toString(),
            actorId,
            action,
            targetType,
            targetId,
            details
        );
    }

    public List<Map<String, Object>> list(int limit, String beforeCursor, String actionFilter) {
        String sql = """
            SELECT 
                al.id,
                al.actor_id,
                u.username as actor_username,
                al.action,
                al.target_type,
                al.target_id,
                al.details,
                al.created_at
            FROM audit_log al
            LEFT JOIN user u ON al.actor_id = u.id
            WHERE 1=1
            """;

        if (beforeCursor != null) {
            sql += " AND al.created_at < (SELECT created_at FROM audit_log WHERE id = ?)";
        }
        if (actionFilter != null) {
            sql += " AND al.action = ?";
        }

        sql += " ORDER BY al.created_at DESC LIMIT ?";

        Object[] params;
        if (beforeCursor != null && actionFilter != null) {
            params = new Object[]{beforeCursor, actionFilter, limit};
        } else if (beforeCursor != null) {
            params = new Object[]{beforeCursor, limit};
        } else if (actionFilter != null) {
            params = new Object[]{actionFilter, limit};
        } else {
            params = new Object[]{limit};
        }

        return db.query(sql, params);
    }
}
