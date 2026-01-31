package chat.kith.auth;

import chat.kith.config.KithFileConfig;
import chat.kith.db.FileRepository;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class FileService {

    private static final Logger LOG = Logger.getLogger(FileService.class);
    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml"
    );
    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            // Images
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml",
            // Documents
            "application/pdf", "text/plain", "text/csv", "text/markdown",
            "application/json", "application/xml",
            // Archives
            "application/zip", "application/gzip", "application/x-tar",
            // Audio
            "audio/mpeg", "audio/ogg", "audio/wav", "audio/webm",
            // Video
            "video/mp4", "video/webm", "video/ogg"
    );

    @Inject
    KithFileConfig config;

    @Inject
    FileRepository fileRepo;

    private Path filesDir;

    @PostConstruct
    void init() {
        filesDir = Path.of(config.dataDir(), "files");
        try {
            Files.createDirectories(filesDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create files directory: " + filesDir, e);
        }
        LOG.infof("[BOOT] FileService ... OK (dir=%s)", filesDir);
    }

    /**
     * Upload a file. Returns map with "id", "stored_name", "url".
     * @throws IllegalArgumentException if MIME type not allowed or size exceeds limit
     */
    public Map<String, String> upload(String originalName, String mimeType, long sizeBytes, InputStream data, String uploaderId) {
        if (!ALLOWED_FILE_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("File type not allowed: " + mimeType);
        }
        if (sizeBytes > config.maxUploadSize()) {
            throw new IllegalArgumentException("File too large: " + sizeBytes + " bytes (max " + config.maxUploadSize() + ")");
        }

        try {
            // Write to temp file first to compute hash
            var tmpFile = Files.createTempFile(filesDir, "upload-", ".tmp");
            Files.copy(data, tmpFile, StandardCopyOption.REPLACE_EXISTING);

            // Verify actual size
            long actualSize = Files.size(tmpFile);
            if (actualSize > config.maxUploadSize()) {
                Files.delete(tmpFile);
                throw new IllegalArgumentException("File too large: " + actualSize + " bytes");
            }

            // Content-addressable name
            String hash = sha256(Files.readAllBytes(tmpFile));
            String ext = extensionFromMime(mimeType);
            String storedName = hash + ext;

            var targetPath = filesDir.resolve(storedName);
            if (Files.exists(targetPath)) {
                Files.delete(tmpFile);
            } else {
                Files.move(tmpFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
            }

            // Reuse existing row if content-addressed file already tracked, else insert
            var existing = fileRepo.findByStoredName(storedName);
            String id;
            if (existing.isPresent()) {
                id = (String) existing.get().get("id");
            } else {
                id = UUID.randomUUID().toString();
                fileRepo.create(id, originalName, storedName, mimeType, actualSize, uploaderId);
            }

            return Map.of(
                    "id", id,
                    "stored_name", storedName,
                    "url", "/api/v0/files/" + storedName
            );
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    /** Get the filesystem path for a stored file */
    public Optional<Path> getFilePath(String storedName) {
        var path = filesDir.resolve(storedName);
        if (Files.exists(path)) return Optional.of(path);
        return Optional.empty();
    }

    public void delete(String fileId) {
        fileRepo.findById(fileId).ifPresent(file -> {
            var storedName = (String) file.get("stored_name");
            try {
                Files.deleteIfExists(filesDir.resolve(storedName));
            } catch (IOException e) {
                LOG.warnf("Failed to delete file from disk: %s", storedName);
            }
            fileRepo.softDelete(fileId);
        });
    }

    private static String sha256(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** Link a file to a message */
    public void linkToMessage(String fileId, String messageId) {
        fileRepo.linkToMessage(fileId, messageId);
    }

    /** Get all files attached to a message */
    public java.util.List<Map<String, Object>> getAttachments(String messageId) {
        return fileRepo.findByMessageId(messageId);
    }

    private static String extensionFromMime(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/svg+xml" -> ".svg";
            case "application/pdf" -> ".pdf";
            case "text/plain" -> ".txt";
            case "text/csv" -> ".csv";
            case "text/markdown" -> ".md";
            case "application/json" -> ".json";
            case "application/xml" -> ".xml";
            case "application/zip" -> ".zip";
            case "application/gzip" -> ".gz";
            case "video/mp4" -> ".mp4";
            case "video/webm" -> ".webm";
            case "audio/mpeg" -> ".mp3";
            case "audio/ogg" -> ".ogg";
            case "audio/wav" -> ".wav";
            default -> "";
        };
    }
}
