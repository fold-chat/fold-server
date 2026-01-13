package chat.fray.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class FileRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String originalName, String storedName, String mimeType, long sizeBytes, String uploaderId) {
        db.execute(
                "INSERT INTO file (id, original_name, stored_name, mime_type, size_bytes, uploader_id) VALUES (?, ?, ?, ?, ?, ?)",
                id, originalName, storedName, mimeType, sizeBytes, uploaderId
        );
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM file WHERE id = ? AND deleted_at IS NULL", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Map<String, Object>> findByStoredName(String storedName) {
        var rows = db.query("SELECT * FROM file WHERE stored_name = ? AND deleted_at IS NULL", storedName);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public void linkToMessage(String fileId, String messageId) {
        db.execute("UPDATE file SET message_id = ? WHERE id = ?", messageId, fileId);
    }

    public List<Map<String, Object>> findByMessageId(String messageId) {
        return db.query("SELECT * FROM file WHERE message_id = ? AND deleted_at IS NULL", messageId);
    }

    public void softDelete(String id) {
        db.execute("UPDATE file SET deleted_at = datetime('now') WHERE id = ?", id);
    }
}
