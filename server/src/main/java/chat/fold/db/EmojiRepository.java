package chat.fold.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class EmojiRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String name, String fileId, String uploaderId) {
        db.execute(
                "INSERT INTO custom_emoji (id, name, file_id, uploader_id) VALUES (?, ?, ?, ?)",
                id, name, fileId, uploaderId
        );
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM custom_emoji WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public Optional<Map<String, Object>> findByName(String name) {
        var rows = db.query("SELECT * FROM custom_emoji WHERE name = ?", name);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> listAll() {
        return db.query(
                "SELECT e.*, f.stored_name, f.mime_type FROM custom_emoji e JOIN file f ON e.file_id = f.id ORDER BY e.created_at ASC"
        );
    }

    public long delete(String id) {
        return db.execute("DELETE FROM custom_emoji WHERE id = ?", id);
    }
}
