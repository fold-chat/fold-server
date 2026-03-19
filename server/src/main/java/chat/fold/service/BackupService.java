package chat.fold.service;

import chat.fold.config.FoldConfig;
import chat.fold.config.FoldFileConfig;
import chat.fold.db.DatabaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@ApplicationScoped
public class BackupService {

    private static final Logger LOG = Logger.getLogger(BackupService.class);

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final int PBKDF2_ITERATIONS = 600_000;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 32;
    private static final String MANIFEST_FILE = "backup-manifest.json";

    @Inject FoldConfig foldConfig;
    @Inject FoldFileConfig fileConfig;
    @Inject DatabaseService db;
    @Inject ObjectMapper objectMapper;

    private volatile boolean restartRequired;

    public boolean isRestartRequired() { return restartRequired; }
    public void setRestartRequired(boolean v) { this.restartRequired = v; }

    public enum BackupArea { DATABASE, FILES, EMOJIS }

    public record BackupResult(String filename, Path path, Map<String, Object> metadata) {}

    public record SizeEstimate(long database, long files, long emojis) {}

    // --- Backup creation ---

    public BackupResult createBackup(Set<BackupArea> areas, String password) throws IOException {
        Path backupsDir = Path.of(fileConfig.dataDir(), "backups");
        Files.createDirectories(backupsDir);

        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        Path tempDir = Files.createTempDirectory(backupsDir, "backup-" + timestamp + "-");

        try {
            // Database
            if (areas.contains(BackupArea.DATABASE)) {
                Path dbBackup = tempDir.resolve("fold.db");
                db.batch("VACUUM INTO '" + dbBackup.toAbsolutePath() + "'");
                if (password != null && !password.isBlank()) {
                    encryptFile(dbBackup, password);
                }
            }

            // Files
            if (areas.contains(BackupArea.FILES)) {
                Path filesDir = Path.of(fileConfig.dataDir(), "files");
                if (Files.isDirectory(filesDir)) {
                    Path targetFilesDir = tempDir.resolve("files");
                    copyDirectory(filesDir, targetFilesDir);
                }
            }

            // Emojis (only if FILES not also selected)
            if (areas.contains(BackupArea.EMOJIS) && !areas.contains(BackupArea.FILES)) {
                var emojiFiles = db.query(
                        "SELECT f.stored_name FROM custom_emoji e JOIN file f ON e.file_id = f.id"
                );
                if (!emojiFiles.isEmpty()) {
                    Path emojisDir = tempDir.resolve("emojis");
                    Files.createDirectories(emojisDir);
                    Path filesDir = Path.of(fileConfig.dataDir(), "files");
                    for (var row : emojiFiles) {
                        String storedName = (String) row.get("stored_name");
                        Path src = filesDir.resolve(storedName);
                        if (Files.exists(src)) {
                            Files.copy(src, emojisDir.resolve(storedName));
                        }
                    }
                }
            }

            // Manifest
            int migrationVersion = getCurrentMigrationVersion();
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("version", 1);
            manifest.put("timestamp", Instant.now().toString());
            manifest.put("areas", areas.stream().map(Enum::name).sorted().toList());
            manifest.put("fold_version", chat.fold.config.BuildInfo.VERSION);
            manifest.put("migration_version", migrationVersion);
            manifest.put("db_encrypted", password != null && !password.isBlank()
                    && areas.contains(BackupArea.DATABASE));
            Files.writeString(tempDir.resolve(MANIFEST_FILE),
                    objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));

            // Tar.gz
            String filename = "fold-backup-" + timestamp + ".tar.gz";
            Path archivePath = backupsDir.resolve(filename);
            createTarGz(tempDir, archivePath);

            var metadata = new LinkedHashMap<String, Object>();
            metadata.put("filename", filename);
            metadata.put("timestamp", manifest.get("timestamp"));
            metadata.put("areas", manifest.get("areas"));
            metadata.put("size_bytes", Files.size(archivePath));
            metadata.put("db_encrypted", manifest.get("db_encrypted"));

            LOG.infof("Backup created: %s (%d bytes)", filename, Files.size(archivePath));
            return new BackupResult(filename, archivePath, metadata);
        } finally {
            deleteDirectory(tempDir);
        }
    }

    // --- Size estimation ---

    public SizeEstimate estimateSize() {
        long dbSize = 0;
        long filesSize = 0;
        long emojisSize = 0;

        // DB size via pragmas
        try {
            var rows = db.query("SELECT page_count * page_size AS size FROM pragma_page_count(), pragma_page_size()");
            if (!rows.isEmpty()) dbSize = (Long) rows.getFirst().get("size");
        } catch (Exception e) {
            LOG.debugf("Failed to estimate DB size: %s", e.getMessage());
        }

        // Files size — walk the actual directory (matches what backup copies)
        try {
            Path filesDir = Path.of(fileConfig.dataDir(), "files");
            if (Files.isDirectory(filesDir)) {
                try (var walker = Files.walk(filesDir)) {
                    filesSize = walker.filter(Files::isRegularFile)
                            .mapToLong(p -> { try { return Files.size(p); } catch (IOException e) { return 0; } })
                            .sum();
                }
            }
        } catch (Exception e) {
            LOG.debugf("Failed to estimate files size: %s", e.getMessage());
        }

        // Emojis — sum actual disk sizes of emoji files
        try {
            var emojiRows = db.query("SELECT f.stored_name FROM custom_emoji e JOIN file f ON e.file_id = f.id");
            Path filesDir = Path.of(fileConfig.dataDir(), "files");
            for (var row : emojiRows) {
                String storedName = (String) row.get("stored_name");
                Path p = filesDir.resolve(storedName);
                if (Files.exists(p)) emojisSize += Files.size(p);
            }
        } catch (Exception e) {
            LOG.debugf("Failed to estimate emoji size: %s", e.getMessage());
        }

        return new SizeEstimate(dbSize, filesSize, emojisSize);
    }

    // --- List/delete backups ---

    public List<Map<String, Object>> listBackups() throws IOException {
        Path backupsDir = Path.of(fileConfig.dataDir(), "backups");
        if (!Files.isDirectory(backupsDir)) return List.of();

        var result = new ArrayList<Map<String, Object>>();
        try (var stream = Files.list(backupsDir)) {
            stream.filter(p -> p.getFileName().toString().endsWith(".tar.gz"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .forEach(p -> {
                        try {
                            var entry = new LinkedHashMap<String, Object>();
                            entry.put("filename", p.getFileName().toString());
                            entry.put("size_bytes", Files.size(p));
                            entry.put("created_at", Files.getLastModifiedTime(p).toInstant().toString());
                            result.add(entry);
                        } catch (IOException ignored) {}
                    });
        }
        return result;
    }

    public boolean deleteBackup(String filename) throws IOException {
        Path backupsDir = Path.of(fileConfig.dataDir(), "backups");
        Path file = backupsDir.resolve(filename);
        if (Files.exists(file) && file.getParent().equals(backupsDir)) {
            Files.delete(file);
            return true;
        }
        return false;
    }

    public Optional<Path> getBackupPath(String filename) {
        Path backupsDir = Path.of(fileConfig.dataDir(), "backups");
        Path file = backupsDir.resolve(filename);
        if (Files.exists(file) && file.getParent().equals(backupsDir)) {
            return Optional.of(file);
        }
        return Optional.empty();
    }

    // --- Restore ---

    @SuppressWarnings("unchecked")
    public Map<String, Object> restore(InputStream archiveStream, String password) throws IOException {
        Path backupsDir = Path.of(fileConfig.dataDir(), "backups");
        Files.createDirectories(backupsDir);
        Path tempDir = Files.createTempDirectory(backupsDir, "restore-");

        try {
            // Extract tar.gz
            extractTarGz(archiveStream, tempDir);

            // Read manifest
            Path manifestPath = tempDir.resolve(MANIFEST_FILE);
            if (!Files.exists(manifestPath)) {
                throw new IllegalArgumentException("Invalid backup: missing " + MANIFEST_FILE);
            }
            var manifest = objectMapper.readValue(Files.readString(manifestPath), LinkedHashMap.class);

            // Version check
            int backupMigration = manifest.get("migration_version") instanceof Number n ? n.intValue() : 0;
            int serverMigration = getCurrentMigrationVersion();
            if (backupMigration > serverMigration) {
                throw new IllegalArgumentException(
                        "Backup is from a newer server (migration " + backupMigration
                                + " > " + serverMigration + "). Update server first.");
            }

            var areas = (List<String>) manifest.getOrDefault("areas", List.of());
            boolean dbEncrypted = Boolean.TRUE.equals(manifest.get("db_encrypted"));

            // Restore database
            if (areas.contains("DATABASE")) {
                Path dbFile = tempDir.resolve("fold.db");
                if (!Files.exists(dbFile)) {
                    throw new IllegalArgumentException("Backup claims DATABASE area but fold.db missing");
                }
                if (dbEncrypted) {
                    if (password == null || password.isBlank()) {
                        throw new IllegalArgumentException("Backup is encrypted — password required");
                    }
                    decryptFile(dbFile, password);
                }
                // Shutdown current DB, replace file
                db.shutdown();
                Path dbTarget = Path.of(foldConfig.path());
                Files.copy(dbFile, dbTarget, StandardCopyOption.REPLACE_EXISTING);
                // Also clean WAL/SHM files
                Files.deleteIfExists(Path.of(foldConfig.path() + "-wal"));
                Files.deleteIfExists(Path.of(foldConfig.path() + "-shm"));
            }

            // Restore files
            Path filesDir = Path.of(fileConfig.dataDir(), "files");
            if (areas.contains("FILES")) {
                Path backupFiles = tempDir.resolve("files");
                if (Files.isDirectory(backupFiles)) {
                    Files.createDirectories(filesDir);
                    copyDirectory(backupFiles, filesDir);
                }
            }

            // Restore emojis
            if (areas.contains("EMOJIS")) {
                Path emojiDir = tempDir.resolve("emojis");
                if (Files.isDirectory(emojiDir)) {
                    Files.createDirectories(filesDir);
                    copyDirectory(emojiDir, filesDir);
                }
            }

            restartRequired = true;
            LOG.info("Backup restored successfully — server restart required");

            return Map.of(
                    "status", "restore_complete",
                    "message", "Backup restored. Please restart the server.",
                    "restart_required", true
            );
        } finally {
            deleteDirectory(tempDir);
        }
    }

    // --- Encryption helpers ---

    private void encryptFile(Path file, String password) throws IOException {
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

            // Write: salt + iv + ciphertext
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

    private void decryptFile(Path file, String password) throws IOException {
        try {
            byte[] data = Files.readAllBytes(file);
            if (data.length < SALT_LENGTH + GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Encrypted file too short");
            }

            byte[] salt = Arrays.copyOfRange(data, 0, SALT_LENGTH);
            byte[] iv = Arrays.copyOfRange(data, SALT_LENGTH, SALT_LENGTH + GCM_IV_LENGTH);
            byte[] ciphertext = Arrays.copyOfRange(data, SALT_LENGTH + GCM_IV_LENGTH, data.length);

            SecretKey key = deriveKey(password, salt);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH, iv));

            byte[] plaintext = cipher.doFinal(ciphertext);
            Files.write(file, plaintext);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Decryption failed — wrong password?", e);
        }
    }

    private SecretKey deriveKey(String password, byte[] salt) throws Exception {
        var spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH);
        var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        byte[] keyBytes = factory.generateSecret(spec).getEncoded();
        return new SecretKeySpec(keyBytes, "AES");
    }

    // --- Tar helpers ---

    private void createTarGz(Path sourceDir, Path outputFile) throws IOException {
        try (var fos = new FileOutputStream(outputFile.toFile());
             var gzos = new GZIPOutputStream(fos);
             var tos = new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(gzos)) {
            tos.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX);
            try (var walker = Files.walk(sourceDir)) {
                walker.filter(p -> !p.equals(sourceDir)).forEach(p -> {
                    try {
                        String entryName = sourceDir.relativize(p).toString();
                        if (Files.isDirectory(p)) {
                            var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(p.toFile(), entryName + "/");
                            tos.putArchiveEntry(entry);
                            tos.closeArchiveEntry();
                        } else {
                            var entry = new org.apache.commons.compress.archivers.tar.TarArchiveEntry(p.toFile(), entryName);
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

    private void extractTarGz(InputStream archiveStream, Path targetDir) throws IOException {
        try (var gzis = new GZIPInputStream(archiveStream);
             var tis = new org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzis)) {
            org.apache.commons.compress.archivers.tar.TarArchiveEntry entry;
            while ((entry = tis.getNextEntry()) != null) {
                Path entryPath = targetDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(targetDir)) {
                    throw new IOException("Tar entry outside target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(tis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    // --- File helpers ---

    private int getCurrentMigrationVersion() {
        try {
            var rows = db.query("SELECT MAX(version) AS v FROM _migrations");
            if (!rows.isEmpty() && rows.getFirst().get("v") != null) {
                return ((Long) rows.getFirst().get("v")).intValue();
            }
        } catch (Exception e) {
            LOG.debugf("Failed to get migration version: %s", e.getMessage());
        }
        return 0;
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        try (var walker = Files.walk(source)) {
            walker.forEach(p -> {
                try {
                    Path dest = target.resolve(source.relativize(p));
                    if (Files.isDirectory(p)) {
                        Files.createDirectories(dest);
                    } else {
                        Files.createDirectories(dest.getParent());
                        Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
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
