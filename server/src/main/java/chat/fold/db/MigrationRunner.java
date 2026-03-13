package chat.fold.db;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
@Startup
public class MigrationRunner {

    private static final Logger LOG = Logger.getLogger(MigrationRunner.class);
    private static final String MIGRATION_DIR = "db/migration";

    @Inject
    DatabaseService db;

    void onStart(@jakarta.enterprise.event.Observes io.quarkus.runtime.StartupEvent ev) {
        createMigrationsTable();
        int applied = applyMigrations();
        LOG.infof("[BOOT] Migrations ... OK (%d applied)", applied);
    }

    private void createMigrationsTable() {
        db.batch("""
                CREATE TABLE IF NOT EXISTS _migrations (
                    version INTEGER PRIMARY KEY,
                    name TEXT NOT NULL,
                    applied_at TEXT NOT NULL
                )
                """);
    }

    private int applyMigrations() {
        var appliedVersions = getAppliedVersions();
        var migrations = scanMigrations();
        int count = 0;

        for (var migration : migrations) {
            if (appliedVersions.contains(migration.version())) continue;
            LOG.infof("Applying migration V%03d: %s", migration.version(), migration.name());
            db.batch(migration.sql());
            db.execute(
                    "INSERT INTO _migrations (version, name, applied_at) VALUES (?, ?, ?)",
                    (long) migration.version(),
                    migration.name(),
                    Instant.now().toString()
            );
            count++;
        }
        return count;
    }

    private Set<Integer> getAppliedVersions() {
        var rows = db.query("SELECT version FROM _migrations");
        return rows.stream()
                .map(r -> ((Long) r.get("version")).intValue())
                .collect(Collectors.toSet());
    }

    private List<Migration> scanMigrations() {
        var cl = Thread.currentThread().getContextClassLoader();
        return MigrationIndex.FILES.stream()
                .map(filename -> {
                    var path = MIGRATION_DIR + "/" + filename;
                    try (InputStream is = cl.getResourceAsStream(path)) {
                        if (is == null) throw new RuntimeException("Migration not found: " + path);
                        return parseMigration(filename, new String(is.readAllBytes(), StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to read migration: " + path, e);
                    }
                })
                .sorted(Comparator.comparingInt(Migration::version))
                .toList();
    }

    private static Migration parseMigration(String filename, String sql) {
        // Expected format: V001__description.sql
        var parts = filename.replace(".sql", "").split("__", 2);
        int version = Integer.parseInt(parts[0].substring(1)); // Remove 'V' prefix
        String name = parts.length > 1 ? parts[1] : filename;
        return new Migration(version, name, sql);
    }

    record Migration(int version, String name, String sql) {}
}
