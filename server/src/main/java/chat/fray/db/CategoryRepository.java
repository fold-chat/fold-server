package chat.fray.db;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class CategoryRepository {

    @Inject
    DatabaseService db;

    public void create(String id, String name, int position) {
        db.execute(
                "INSERT INTO category (id, name, position) VALUES (?, ?, ?)",
                id, name, position
        );
    }

    public Optional<Map<String, Object>> findById(String id) {
        var rows = db.query("SELECT * FROM category WHERE id = ?", id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    public List<Map<String, Object>> listAll() {
        return db.query("SELECT * FROM category ORDER BY position, created_at");
    }

    public void update(String id, String name, Integer position) {
        if (position != null) {
            db.execute("UPDATE category SET name = ?, position = ? WHERE id = ?", name, position, id);
        } else {
            db.execute("UPDATE category SET name = ? WHERE id = ?", name, id);
        }
    }

    public void delete(String id) {
        // Channels in this category get category_id set to NULL (ON DELETE SET NULL)
        db.execute("DELETE FROM category WHERE id = ?", id);
    }

    public int nextPosition() {
        var rows = db.query("SELECT COALESCE(MAX(position), -1) + 1 AS next_pos FROM category");
        return ((Long) rows.getFirst().get("next_pos")).intValue();
    }
}
