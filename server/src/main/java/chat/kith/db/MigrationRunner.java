package chat.kith.db;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
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
        var migrations = new ArrayList<Migration>();
        var cl = Thread.currentThread().getContextClassLoader();

        try {
            var resources = cl.getResources(MIGRATION_DIR);
            while (resources.hasMoreElements()) {
                var url = resources.nextElement();
                if ("file".equals(url.getProtocol())) {
                    var dir = Path.of(url.toURI());
                    try (var stream = Files.list(dir)) {
                        stream.filter(p -> p.toString().endsWith(".sql"))
                                .sorted()
                                .forEach(p -> migrations.add(parseMigration(p.getFileName().toString(),
                                        readFile(p))));
                    }
                } else if ("jar".equals(url.getProtocol())) {
                    // Handle classpath scanning in JAR mode
                    var jarPath = url.toURI().toString().split("!")[0].replace("jar:", "");
                    try (var fs = FileSystems.newFileSystem(Path.of(java.net.URI.create(jarPath)));
                         var stream = Files.list(fs.getPath(MIGRATION_DIR))) {
                        stream.filter(p -> p.toString().endsWith(".sql"))
                                .sorted()
                                .forEach(p -> migrations.add(parseMigration(p.getFileName().toString(),
                                        readFile(p))));
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException("Failed to scan migrations", e);
        }

        migrations.sort(Comparator.comparingInt(Migration::version));
        return migrations;
    }

    private static Migration parseMigration(String filename, String sql) {
        // Expected format: V001__description.sql
        var parts = filename.replace(".sql", "").split("__", 2);
        int version = Integer.parseInt(parts[0].substring(1)); // Remove 'V' prefix
        String name = parts.length > 1 ? parts[1] : filename;
        return new Migration(version, name, sql);
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read migration: " + path, e);
        }
    }

    record Migration(int version, String name, String sql) {}
}
