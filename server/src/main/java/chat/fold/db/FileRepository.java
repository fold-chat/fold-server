package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class FileRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String originalName, String storedName, String mimeType, long sizeBytes, String uploaderId,
                       String thumbnailStoredName, String processingStatus, Double durationSeconds, Integer width, Integer height) {
        db.execute(
                "INSERT INTO file (id, original_name, stored_name, mime_type, size_bytes, uploader_id, thumbnail_stored_name, processing_status, duration_seconds, width, height) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, originalName, storedName, mimeType, sizeBytes, uploaderId,
                thumbnailStoredName, processingStatus != null ? processingStatus : "complete",
                durationSeconds, width, height
        );
    }

    public void updateProcessingResult(String id, String storedName, String thumbnailStoredName, String status,
                                       Double durationSeconds, Integer width, Integer height) {
        if (storedName != null) {
            db.execute("UPDATE file SET stored_name = ?, thumbnail_stored_name = ?, processing_status = ?, duration_seconds = ?, width = ?, height = ? WHERE id = ?",
                    storedName, thumbnailStoredName, status, durationSeconds, width, height, id);
        } else {
            db.execute("UPDATE file SET processing_status = ? WHERE id = ?", status, id);
        }
    }

    public void updateMimeAndSize(String id, String mimeType, long sizeBytes) {
        db.execute("UPDATE file SET mime_type = ?, size_bytes = ? WHERE id = ?", mimeType, sizeBytes, id);
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
