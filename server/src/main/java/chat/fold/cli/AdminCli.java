package chat.fold.cli;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** CLI admin command dispatcher. Runs without Quarkus CDI. */
public final class AdminCli {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 32;

    private AdminCli() {}

    public static int run(String[] args) {
        if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
            printUsage();
            return args.length == 0 ? 2 : 0;
        }

        String command = args[0];
        String[] flags = Arrays.copyOfRange(args, 1, args.length);

        return switch (command) {
            case "reset-password" -> resetPassword(flags);
            case "unlock" -> unlock(flags);
            case "create-backup" -> createBackup(flags);
            case "list-users" -> listUsers(flags);
            case "server-info" -> serverInfo(flags);
            default -> {
                System.err.println("Unknown command: " + command);
                printUsage();
                yield 2;
            }
        };
    }

    // --- Commands ---

    private static int resetPassword(String[] flags) {
        String username = getFlag(flags, "--username");
        if (username == null) {
            System.err.println("Usage: admin reset-password --username=<user>");
            return 2;
        }

        try (var db = openDb(flags)) {
            var rows = db.query("SELECT id, username FROM user WHERE username = ? COLLATE NOCASE AND deleted_at IS NULL", username);
            if (rows.isEmpty()) {
                System.err.println("User not found: " + username);
                return 1;
            }

            String userId = (String) rows.getFirst().get("id");
            String actualUsername = (String) rows.getFirst().get("username");
            String tempPassword = AdminPasswordHelper.generateTempPassword();
            String hash = AdminPasswordHelper.hash(tempPassword);

            db.execute("UPDATE user SET password_hash = ?, password_must_change = 1 WHERE id = ?", hash, userId);
            db.execute("DELETE FROM session WHERE user_id = ?", userId);

            System.out.println("Password reset for '" + actualUsername + "'");
            System.out.println("Temporary password: " + tempPassword);
            System.out.println("User will be required to change password on next login.");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int unlock(String[] flags) {
        String username = getFlag(flags, "--username");
        if (username == null) {
            System.err.println("Usage: admin unlock --username=<user>");
            return 2;
        }

        try (var db = openDb(flags)) {
            var rows = db.query("SELECT id, username, locked_until, failed_login_count FROM user WHERE username = ? COLLATE NOCASE AND deleted_at IS NULL", username);
            if (rows.isEmpty()) {
                System.err.println("User not found: " + username);
                return 1;
            }

            String userId = (String) rows.getFirst().get("id");
            String actualUsername = (String) rows.getFirst().get("username");
            String lockedUntil = (String) rows.getFirst().get("locked_until");
            Long failCount = (Long) rows.getFirst().get("failed_login_count");

            if (lockedUntil == null && (failCount == null || failCount == 0)) {
                System.out.println("Account '" + actualUsername + "' is not locked.");
                return 0;
            }

            db.execute("UPDATE user SET failed_login_count = 0, locked_until = NULL WHERE id = ?", userId);
            System.out.println("Account '" + actualUsername + "' unlocked.");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int createBackup(String[] flags) {
        String areasStr = getFlag(flags, "--areas");
        String password = getFlag(flags, "--password");
        String dataDir = resolveDataDir(flags);

        Set<String> areas = new LinkedHashSet<>();
        if (areasStr != null) {
            for (String a : areasStr.split(",")) {
                String trimmed = a.trim().toUpperCase();
                if (!Set.of("DATABASE", "FILES", "EMOJIS").contains(trimmed)) {
                    System.err.println("Unknown backup area: " + a.trim());
                    return 2;
                }
                areas.add(trimmed);
            }
        } else {
            areas.add("DATABASE");
        }

        try (var db = openDb(flags)) {
            Path backupsDir = Path.of(dataDir, "backups");
            Files.createDirectories(backupsDir);

            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                    .withZone(ZoneOffset.UTC).format(Instant.now());
            Path tempDir = Files.createTempDirectory(backupsDir, "backup-" + timestamp + "-");

            try {
                // Database
                if (areas.contains("DATABASE")) {
                    Path dbBackup = tempDir.resolve("fold.db");
                    db.execute("VACUUM INTO '" + dbBackup.toAbsolutePath() + "'");
                    if (password != null && !password.isBlank()) {
                        encryptFile(dbBackup, password);
                    }
                    System.out.println("  Database ... OK");
                }

                // Files
                if (areas.contains("FILES")) {
                    Path filesDir = Path.of(dataDir, "files");
                    if (Files.isDirectory(filesDir)) {
                        copyDirectory(filesDir, tempDir.resolve("files"));
                        System.out.println("  Files ... OK");
                    } else {
                        System.out.println("  Files ... skipped (no files directory)");
                    }
                }

                // Emojis (only if FILES not also selected)
                if (areas.contains("EMOJIS") && !areas.contains("FILES")) {
                    var emojiRows = db.query("SELECT f.stored_name FROM custom_emoji e JOIN file f ON e.file_id = f.id");
                    if (!emojiRows.isEmpty()) {
                        Path emojisDir = tempDir.resolve("emojis");
                        Files.createDirectories(emojisDir);
                        Path filesDir = Path.of(dataDir, "files");
                        for (var row : emojiRows) {
                            String storedName = (String) row.get("stored_name");
                            Path src = filesDir.resolve(storedName);
                            if (Files.exists(src)) {
                                Files.copy(src, emojisDir.resolve(storedName));
                            }
                        }
                        System.out.println("  Emojis ... OK (" + emojiRows.size() + " files)");
                    } else {
                        System.out.println("  Emojis ... skipped (none found)");
                    }
                }

                // Manifest
                int migrationVersion = getMigrationVersion(db);
                String manifest = """
                        {
                          "version": 1,
                          "timestamp": "%s",
                          "areas": [%s],
                          "fold_version": "cli",
                          "migration_version": %d,
                          "db_encrypted": %s
                        }""".formatted(
                        Instant.now().toString(),
                        String.join(", ", areas.stream().map(a -> "\"" + a + "\"").toList()),
                        migrationVersion,
                        password != null && !password.isBlank() && areas.contains("DATABASE")
                );
                Files.writeString(tempDir.resolve("backup-manifest.json"), manifest);

                // Tar.gz
                String filename = "fold-backup-" + timestamp + ".tar.gz";
                Path archivePath = backupsDir.resolve(filename);
                createTarGz(tempDir, archivePath);

                long sizeBytes = Files.size(archivePath);
                System.out.println("Backup created: " + filename + " (" + formatSize(sizeBytes) + ")");
                return 0;
            } finally {
                deleteDirectory(tempDir);
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int listUsers(String[] flags) {
        boolean includeBanned = hasFlag(flags, "--include-banned");
        boolean includeLocked = hasFlag(flags, "--include-locked");

        try (var db = openDb(flags)) {
            var rows = db.query("""
                    SELECT u.username, u.display_name, u.created_at, u.last_seen_at,
                           u.banned_at, u.locked_until, u.failed_login_count,
                           GROUP_CONCAT(r.name) AS roles
                    FROM user u
                    LEFT JOIN user_role ur ON u.id = ur.user_id
                    LEFT JOIN role r ON ur.role_id = r.id
                    WHERE u.deleted_at IS NULL AND u.is_bot = 0
                    GROUP BY u.id
                    ORDER BY u.created_at
                    """);

            if (rows.isEmpty()) {
                System.out.println("No users found.");
                return 0;
            }

            // Compute column widths
            int wUser = "USERNAME".length(), wDisplay = "DISPLAY NAME".length(),
                wRoles = "ROLES".length(), wStatus = "STATUS".length(), wCreated = "CREATED".length();

            var filtered = new ArrayList<Map<String, Object>>();
            for (var row : rows) {
                String status = userStatus(row);
                if (!includeBanned && status.contains("banned")) continue;
                if (!includeLocked && status.contains("locked")) continue;

                String username = str(row, "username");
                String displayName = str(row, "display_name");
                String roles = str(row, "roles");
                String created = str(row, "created_at");
                if (created.length() > 10) created = created.substring(0, 10);

                wUser = Math.max(wUser, username.length());
                wDisplay = Math.max(wDisplay, displayName.length());
                wRoles = Math.max(wRoles, roles.length());
                wStatus = Math.max(wStatus, status.length());
                wCreated = Math.max(wCreated, created.length());

                row.put("_status", status);
                row.put("_created_short", created);
                filtered.add(row);
            }

            if (filtered.isEmpty()) {
                System.out.println("No users match filters.");
                return 0;
            }

            String fmt = "  %-" + wUser + "s  %-" + wDisplay + "s  %-" + wRoles + "s  %-" + wStatus + "s  %-" + wCreated + "s%n";
            System.out.printf(fmt, "USERNAME", "DISPLAY NAME", "ROLES", "STATUS", "CREATED");
            System.out.printf(fmt, "-".repeat(wUser), "-".repeat(wDisplay), "-".repeat(wRoles), "-".repeat(wStatus), "-".repeat(wCreated));

            for (var row : filtered) {
                System.out.printf(fmt,
                        str(row, "username"),
                        str(row, "display_name"),
                        str(row, "roles"),
                        str(row, "_status"),
                        str(row, "_created_short"));
            }

            System.out.println("\n" + filtered.size() + " user(s)");
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static int serverInfo(String[] flags) {
        try (var db = openDb(flags)) {
            String dbPath = resolveDbPath(flags);
            long dbSize = 0;
            try {
                var sizeRows = db.query("SELECT page_count * page_size AS size FROM pragma_page_count(), pragma_page_size()");
                if (!sizeRows.isEmpty()) dbSize = (Long) sizeRows.getFirst().get("size");
            } catch (Exception ignored) {}

            long userCount = count(db, "SELECT COUNT(*) AS cnt FROM user WHERE deleted_at IS NULL AND is_bot = 0");
            long botCount = count(db, "SELECT COUNT(*) AS cnt FROM user WHERE deleted_at IS NULL AND is_bot = 1");
            long channelCount = count(db, "SELECT COUNT(*) AS cnt FROM channel");
            long messageCount = count(db, "SELECT COUNT(*) AS cnt FROM message");
            int migrationVersion = getMigrationVersion(db);

            boolean maintenance = false;
            var mRows = db.query("SELECT value FROM server_config WHERE key = 'maintenance_enabled'");
            if (!mRows.isEmpty()) maintenance = "true".equals(mRows.getFirst().get("value"));

            String serverName = "Fold";
            var nRows = db.query("SELECT value FROM server_config WHERE key = 'server_name'");
            if (!nRows.isEmpty() && nRows.getFirst().get("value") != null) {
                serverName = (String) nRows.getFirst().get("value");
            }

            System.out.println("Server:     " + serverName);
            System.out.println("DB path:    " + dbPath);
            System.out.println("DB size:    " + formatSize(dbSize));
            System.out.println("Migration:  v" + migrationVersion);
            System.out.println("Users:      " + userCount + (botCount > 0 ? " (+" + botCount + " bots)" : ""));
            System.out.println("Channels:   " + channelCount);
            System.out.println("Messages:   " + messageCount);
            System.out.println("Maintenance:" + (maintenance ? " ENABLED" : " off"));
            return 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    // --- Help ---

    private static void printUsage() {
        System.out.println("""
                Fold Admin CLI

                Usage: fold admin <command> [flags]

                Commands:
                  reset-password  Reset a user's password
                  unlock          Unlock a locked account
                  create-backup   Create a server backup
                  list-users      List all users
                  server-info     Show server information

                Global flags:
                  --db-path=<path>    Override FOLD_DB_PATH (default: fold.db)
                  --data-dir=<path>   Override FOLD_DATA_DIR (default: ./data)

                Examples:
                  fold admin reset-password --username=alice
                  fold admin unlock --username=bob
                  fold admin create-backup --areas=database,files
                  fold admin create-backup --areas=database --password=secret
                  fold admin list-users --include-banned
                  fold admin server-info""");
    }

    // --- Flag parsing ---

    private static String getFlag(String[] flags, String name) {
        String prefix = name + "=";
        for (String f : flags) {
            if (f.startsWith(prefix)) return f.substring(prefix.length());
        }
        return null;
    }

    private static boolean hasFlag(String[] flags, String name) {
        for (String f : flags) {
            if (f.equals(name)) return true;
        }
        return false;
    }

    // --- DB helpers ---

    private static AdminDbHelper openDb(String[] flags) {
        return new AdminDbHelper(resolveDbPath(flags));
    }

    private static String resolveDbPath(String[] flags) {
        String fromFlag = getFlag(flags, "--db-path");
        if (fromFlag != null) return fromFlag;
        String fromEnv = System.getenv("FOLD_DB_PATH");
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : "fold.db";
    }

    private static String resolveDataDir(String[] flags) {
        String fromFlag = getFlag(flags, "--data-dir");
        if (fromFlag != null) return fromFlag;
        String fromEnv = System.getenv("FOLD_DATA_DIR");
        return (fromEnv != null && !fromEnv.isBlank()) ? fromEnv : "./data";
    }

    private static long count(AdminDbHelper db, String sql) {
        var rows = db.query(sql);
        return rows.isEmpty() ? 0 : (Long) rows.getFirst().get("cnt");
    }

    private static int getMigrationVersion(AdminDbHelper db) {
        try {
            var rows = db.query("SELECT MAX(version) AS v FROM _migrations");
            if (!rows.isEmpty() && rows.getFirst().get("v") != null) {
                return ((Long) rows.getFirst().get("v")).intValue();
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private static String str(Map<String, Object> row, String key) {
        var val = row.get(key);
        return val != null ? val.toString() : "";
    }

    private static String userStatus(Map<String, Object> row) {
        if (row.get("banned_at") != null) return "banned";
        if (row.get("locked_until") != null) return "locked";
        Long failCount = (Long) row.get("failed_login_count");
        if (failCount != null && failCount > 0) return failCount + " failed";
        return "active";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return "%.1f KB".formatted(bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return "%.1f MB".formatted(bytes / (1024.0 * 1024));
        return "%.1f GB".formatted(bytes / (1024.0 * 1024 * 1024));
    }

    // --- Backup: encryption (matches BackupService format) ---

    private static void encryptFile(Path file, String password) throws IOException {
        try {
            byte[] salt = new byte[SALT_LENGTH];
            new SecureRandom().nextBytes(salt);
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            SecretKey key = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plaintext = Files.readAllBytes(file);
            byte[] ciphertext = cipher.doFinal(plaintext);

            try (var out = new FileOutputStream(file.toFile())) {
                out.write(salt);
                out.write(iv);
                out.write(ciphertext);
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Encryption failed", e);
        }
    }

    private static SecretKey deriveKey(String password, byte[] salt) throws Exception {
        var spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    // --- Backup: tar.gz (matches BackupService format) ---

    private static void createTarGz(Path sourceDir, Path outputFile) throws IOException {
        try (var fos = new FileOutputStream(outputFile.toFile());
             var gzos = new GZIPOutputStream(fos);
             var tos = new TarArchiveOutputStream(gzos)) {
            tos.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            try (var walker = Files.walk(sourceDir)) {
                walker.filter(p -> !p.equals(sourceDir)).forEach(p -> {
                    try {
                        String entryName = sourceDir.relativize(p).toString();
                        if (Files.isDirectory(p)) {
                            var entry = new TarArchiveEntry(p.toFile(), entryName + "/");
                            tos.putArchiveEntry(entry);
                            tos.closeArchiveEntry();
                        } else {
                            var entry = new TarArchiveEntry(p.toFile(), entryName);
                            entry.setSize(Files.size(p));
                            tos.putArchiveEntry(entry);
                            Files.copy(p, tos);
                            tos.closeArchiveEntry();
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            }
        }
    }

    // --- Backup: file helpers ---

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var walker = Files.walk(source)) {
            walker.forEach(p -> {
                try {
                    Path dest = target.resolve(source.relativize(p));
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(p, dest);
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void deleteDirectory(Path dir) {
        try (var walker = Files.walk(dir)) {
            walker.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
