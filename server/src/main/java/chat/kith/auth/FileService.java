package chat.kith.auth;

import chat.kith.config.KithFileConfig;
import chat.kith.config.KithMediaProcessingConfig;
import chat.kith.config.RuntimeConfigService;
import chat.kith.db.FileRepository;
import chat.kith.event.Event;
import chat.kith.event.EventBus;
import chat.kith.event.EventType;
import chat.kith.event.Scope;
import chat.kith.service.MediaProcessingService;
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
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@ApplicationScoped
public class FileService {

    private static final Logger LOG = Logger.getLogger(FileService.class);

    /** Images that get processed (metadata strip + thumbnail). SVG excluded — download-only. */
    private static final Set<String> PROCESSABLE_IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/heic"
    );

    private static final Set<String> VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/ogg", "video/quicktime", "video/x-matroska", "video/x-msvideo"
    );

    private static final Set<String> ALLOWED_FILE_TYPES = Set.of(
            // Images (including SVG as download-only, HEIC converted to JPEG)
            "image/png", "image/jpeg", "image/gif", "image/webp", "image/svg+xml", "image/heic",
            // Documents
            "application/pdf", "text/plain", "text/csv", "text/markdown",
            "application/json", "application/xml",
            // Archives
            "application/zip", "application/gzip", "application/x-tar",
            // Audio
            "audio/mpeg", "audio/ogg", "audio/wav", "audio/webm",
            // Video
            "video/mp4", "video/webm", "video/ogg", "video/quicktime", "video/x-matroska", "video/x-msvideo"
    );

    @Inject KithFileConfig config;
    @Inject KithMediaProcessingConfig mediaConfig;
    @Inject RuntimeConfigService runtimeConfig;
    @Inject FileRepository fileRepo;
    @Inject MediaProcessingService mediaService;
    @Inject EventBus eventBus;

    private Path filesDir;

    @PostConstruct
    void init() {
        filesDir = Path.of(config.dataDir(), "files").toAbsolutePath();
        try {
            Files.createDirectories(filesDir);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create files directory: " + filesDir, e);
        }
        LOG.infof("[BOOT] FileService ... OK (dir=%s)", filesDir);
    }

    /**
     * Upload a file. Returns map with "id", "stored_name", "url", "processing_status".
     * @throws IllegalArgumentException if MIME type not allowed, size exceeds limit, or magic bytes mismatch
     */
    public Map<String, String> upload(String originalName, String mimeType, long sizeBytes, InputStream data, String uploaderId) {
        // Sanitize filename
        String safeName = sanitizeFilename(originalName);

        if (!ALLOWED_FILE_TYPES.contains(mimeType)) {
            throw new IllegalArgumentException("File type not allowed: " + mimeType);
        }

        // Video mode check — runtime config overrides @ConfigMapping
        boolean isVideo = VIDEO_TYPES.contains(mimeType);
        String videoMode = runtimeConfig.getString("kith.media-processing.video-mode", mediaConfig.videoMode());
        if (isVideo && "disabled".equals(videoMode)) {
            throw new IllegalArgumentException("Video uploads are disabled");
        }

        // Size check based on type — runtime config overrides @ConfigMapping
        long maxVideoSize = parseLong(runtimeConfig.getString("kith.media-processing.max-video-size", null), mediaConfig.maxVideoSize());
        long maxImageSize = parseLong(runtimeConfig.getString("kith.media-processing.max-image-size", null), mediaConfig.maxImageSize());
        long maxSize = isVideo ? maxVideoSize
                : PROCESSABLE_IMAGE_TYPES.contains(mimeType) ? maxImageSize
                : config.maxUploadSize();
        if (sizeBytes > maxSize) {
            throw new IllegalArgumentException("File too large: " + sizeBytes + " bytes (max " + maxSize + ")");
        }

        try {
            var tmpFile = Files.createTempFile(filesDir, "upload-", ".tmp");
            Files.copy(data, tmpFile, StandardCopyOption.REPLACE_EXISTING);

            long actualSize = Files.size(tmpFile);
            if (actualSize > maxSize) {
                Files.delete(tmpFile);
                throw new IllegalArgumentException("File too large: " + actualSize + " bytes");
            }

            // Magic bytes validation
            if (!mediaService.validateMagicBytes(tmpFile, mimeType)) {
                Files.delete(tmpFile);
                throw new IllegalArgumentException("File content does not match declared type: " + mimeType);
            }

            // Route by type
            if (PROCESSABLE_IMAGE_TYPES.contains(mimeType)) {
                return processImage(tmpFile, safeName, mimeType, actualSize, uploaderId);
            } else if (isVideo) {
                return processVideo(tmpFile, safeName, mimeType, actualSize, uploaderId);
            } else {
                return storeGenericFile(tmpFile, safeName, mimeType, actualSize, uploaderId);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file", e);
        }
    }

    /** Process image: strip metadata, generate thumbnail, store both. */
    private Map<String, String> processImage(Path tmpFile, String originalName, String mimeType, long actualSize, String uploaderId) throws IOException {
        Path processedFile = tmpFile;
        Path thumbFile = null;
        String thumbnailStoredName = null;

        try {
            String ext = extensionFromMime(mimeType);

            // HEIC → JPEG conversion (browsers can't render HEIC)
            if ("image/heic".equals(mimeType)) {
                if (!mediaService.canConvertHeic()) {
                    throw new IllegalArgumentException("HEIC conversion requires sips, ImageMagick, or ffmpeg");
                }
                var converted = mediaService.convertHeicToJpeg(tmpFile);
                if (converted.isEmpty()) {
                    throw new IllegalArgumentException("Failed to convert HEIC image");
                }
                processedFile = converted.get();
                actualSize = Files.size(processedFile);
                ext = ".jpg";
                mimeType = "image/jpeg";
            } else {
                // Strip metadata
                var stripped = mediaService.stripImageMetadata(tmpFile, ext);
                if (stripped.isPresent()) {
                    processedFile = stripped.get();
                    actualSize = Files.size(processedFile);
                }
            }

            // Generate thumbnail
            var thumb = mediaService.generateImageThumbnail(processedFile, mimeType);
            if (thumb.isPresent()) {
                thumbFile = thumb.get();
                String thumbHash = sha256(Files.readAllBytes(thumbFile));
                thumbnailStoredName = thumbHash + ".jpg";
                var thumbTarget = filesDir.resolve(thumbnailStoredName);
                if (!Files.exists(thumbTarget)) {
                    Files.move(thumbFile, thumbTarget, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.deleteIfExists(thumbFile);
                }
                thumbFile = null; // moved/cleaned
            }

            // Store main file
            String hash = sha256(Files.readAllBytes(processedFile));
            String storedName = hash + ext;
            var targetPath = filesDir.resolve(storedName);
            if (Files.exists(targetPath)) {
                Files.deleteIfExists(processedFile);
            } else {
                Files.move(processedFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
            }
            processedFile = null; // moved

            // DB
            var existing = fileRepo.findByStoredName(storedName);
            String id;
            if (existing.isPresent()) {
                id = (String) existing.get().get("id");
            } else {
                id = UUID.randomUUID().toString();
                fileRepo.create(id, originalName, storedName, mimeType, actualSize, uploaderId,
                        thumbnailStoredName, "complete", null, null, null);
            }

            return Map.of("id", id, "stored_name", storedName,
                    "url", "/api/v0/files/" + storedName, "processing_status", "complete");
        } finally {
            // Cleanup temps on failure
            if (processedFile != null && !processedFile.equals(tmpFile)) mediaService.cleanupTemp(processedFile);
            if (thumbFile != null) mediaService.cleanupTemp(thumbFile);
            Files.deleteIfExists(tmpFile);
        }
    }

    /** Process video based on configured video mode. */
    private Map<String, String> processVideo(Path tmpFile, String originalName, String mimeType, long actualSize, String uploaderId) throws IOException {
        String videoMode = runtimeConfig.getString("kith.media-processing.video-mode", mediaConfig.videoMode());

        // Duration/size validation — runtime config overrides @ConfigMapping
        int maxDuration = runtimeConfig.getInt("kith.media-processing.max-video-duration", mediaConfig.maxVideoDuration());
        if (mediaService.isFfmpegAvailable()) {
            var duration = mediaService.getVideoDuration(tmpFile);
            if (duration.isPresent() && duration.get() > maxDuration) {
                Files.delete(tmpFile);
                throw new IllegalArgumentException("Video too long: " + duration.get().intValue() + "s (max " + maxDuration + "s)");
            }
        }

        // Store original
        String hash = sha256(Files.readAllBytes(tmpFile));
        String ext = extensionFromMime(mimeType);
        String storedName = hash + ext;
        var targetPath = filesDir.resolve(storedName);
        if (Files.exists(targetPath)) {
            Files.delete(tmpFile);
        } else {
            Files.move(tmpFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
        }

        if ("transcode".equals(videoMode) && mediaService.isFfmpegAvailable()) {
            // Async transcode — check for existing content-addressed file
            var existing = fileRepo.findByStoredName(storedName);
            String id;
            if (existing.isPresent()) {
                id = (String) existing.get().get("id");
            } else {
                id = UUID.randomUUID().toString();
                fileRepo.create(id, originalName, storedName, mimeType, actualSize, uploaderId,
                        null, "processing", null, null, null);
                // Kick off async processing
                Thread.ofVirtual().name("transcode-" + id).start(() -> transcodeAsync(id, storedName, targetPath));
            }
            String status = existing.isPresent() ? (String) existing.get().get("processing_status") : "processing";
            return Map.of("id", id, "stored_name", storedName,
                    "url", "/api/v0/files/" + storedName, "processing_status", status);
        } else {
            // no-transcode: generate thumbnail if possible, store as-is
            String thumbnailStoredName = null;
            Double durationSecs = null;
            Integer width = null, height = null;

            if (mediaService.isFfmpegAvailable()) {
                var thumb = mediaService.generateVideoThumbnail(targetPath);
                if (thumb.isPresent()) {
                    String thumbHash = sha256(Files.readAllBytes(thumb.get()));
                    thumbnailStoredName = thumbHash + ".jpg";
                    var thumbTarget = filesDir.resolve(thumbnailStoredName);
                    if (!Files.exists(thumbTarget)) {
                        Files.move(thumb.get(), thumbTarget, StandardCopyOption.ATOMIC_MOVE);
                    } else {
                        Files.deleteIfExists(thumb.get());
                    }
                }
                durationSecs = mediaService.getVideoDuration(targetPath).orElse(null);
                var dims = mediaService.getVideoDimensions(targetPath);
                if (dims.isPresent()) {
                    width = dims.get()[0];
                    height = dims.get()[1];
                }
            }

            var existing = fileRepo.findByStoredName(storedName);
            String id;
            if (existing.isPresent()) {
                id = (String) existing.get().get("id");
            } else {
                id = UUID.randomUUID().toString();
                fileRepo.create(id, originalName, storedName, mimeType, actualSize, uploaderId,
                        thumbnailStoredName, "complete", durationSecs, width, height);
            }

            return Map.of("id", id, "stored_name", storedName,
                    "url", "/api/v0/files/" + storedName, "processing_status", "complete");
        }
    }

    /** Async video transcode — runs on virtual thread. */
    private void transcodeAsync(String fileId, String originalStoredName, Path originalPath) {
        try {
            // Transcode
            var transcoded = mediaService.transcodeVideo(originalPath);
            if (transcoded.isEmpty()) {
                fileRepo.updateProcessingResult(fileId, null, null, "failed", null, null, null);
                publishAttachmentUpdate(fileId);
                return;
            }

            // Store transcoded file
            String newHash = sha256(Files.readAllBytes(transcoded.get()));
            String newStoredName = newHash + ".mp4";
            long newSize = Files.size(transcoded.get());
            var newTarget = filesDir.resolve(newStoredName);
            if (!Files.exists(newTarget)) {
                Files.move(transcoded.get(), newTarget, StandardCopyOption.ATOMIC_MOVE);
            } else {
                Files.deleteIfExists(transcoded.get());
            }

            // Thumbnail
            String thumbnailStoredName = null;
            var thumb = mediaService.generateVideoThumbnail(newTarget);
            if (thumb.isPresent()) {
                String thumbHash = sha256(Files.readAllBytes(thumb.get()));
                thumbnailStoredName = thumbHash + ".jpg";
                var thumbTarget = filesDir.resolve(thumbnailStoredName);
                if (!Files.exists(thumbTarget)) {
                    Files.move(thumb.get(), thumbTarget, StandardCopyOption.ATOMIC_MOVE);
                } else {
                    Files.deleteIfExists(thumb.get());
                }
            }

            // Duration + dimensions
            Double duration = mediaService.getVideoDuration(newTarget).orElse(null);
            Integer width = null, height = null;
            var dims = mediaService.getVideoDimensions(newTarget);
            if (dims.isPresent()) {
                width = dims.get()[0];
                height = dims.get()[1];
            }

            // Delete original if different from transcoded
            if (!newStoredName.equals(originalStoredName)) {
                Files.deleteIfExists(originalPath);
            }

            // Update DB — stored_name changes to transcoded, mime becomes video/mp4
            fileRepo.updateProcessingResult(fileId, newStoredName, thumbnailStoredName, "complete",
                    duration, width, height);
            fileRepo.updateMimeAndSize(fileId, "video/mp4", newSize);

            publishAttachmentUpdate(fileId);
            LOG.infof("Transcode complete for file %s", fileId);
        } catch (Exception e) {
            LOG.errorf("Transcode failed for file %s: %s", fileId, e.getMessage());
            fileRepo.updateProcessingResult(fileId, null, null, "failed", null, null, null);
            publishAttachmentUpdate(fileId);
        }
    }

    private void publishAttachmentUpdate(String fileId) {
        fileRepo.findById(fileId).ifPresent(file -> {
            var messageId = (String) file.get("message_id");
            if (messageId != null) {
                var payload = new HashMap<String, Object>(file);
                payload.put("url", "/api/v0/files/" + file.get("stored_name"));
                if (file.get("thumbnail_stored_name") != null) {
                payload.put("thumbnail_url", "/api/v0/files/" + file.get("stored_name") + "/thumbnail");
                }
                eventBus.publish(Event.of(EventType.ATTACHMENT_UPDATE,
                        Map.of("message_id", messageId, "attachment", payload),
                        Scope.server()));
            }
        });
    }

    /** Store a generic (non-media) file. */
    private Map<String, String> storeGenericFile(Path tmpFile, String originalName, String mimeType, long actualSize, String uploaderId) throws IOException {
        String hash = sha256(Files.readAllBytes(tmpFile));
        String ext = extensionFromMime(mimeType);
        String storedName = hash + ext;
        var targetPath = filesDir.resolve(storedName);
        if (Files.exists(targetPath)) {
            Files.delete(tmpFile);
        } else {
            Files.move(tmpFile, targetPath, StandardCopyOption.ATOMIC_MOVE);
        }

        var existing = fileRepo.findByStoredName(storedName);
        String id;
        if (existing.isPresent()) {
            id = (String) existing.get().get("id");
        } else {
            id = UUID.randomUUID().toString();
            fileRepo.create(id, originalName, storedName, mimeType, actualSize, uploaderId,
                    null, "complete", null, null, null);
        }

        return Map.of("id", id, "stored_name", storedName,
                "url", "/api/v0/files/" + storedName, "processing_status", "complete");
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
            var thumbName = (String) file.get("thumbnail_stored_name");
            try {
                Files.deleteIfExists(filesDir.resolve(storedName));
                if (thumbName != null) Files.deleteIfExists(filesDir.resolve(thumbName));
            } catch (IOException e) {
                LOG.warnf("Failed to delete file from disk: %s", storedName);
            }
            fileRepo.softDelete(fileId);
        });
    }

    static String sha256(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public void linkToMessage(String fileId, String messageId) {
        fileRepo.linkToMessage(fileId, messageId);
    }

    public List<Map<String, Object>> getAttachments(String messageId) {
        return fileRepo.findByMessageId(messageId);
    }

    /** Sanitize filename: strip path separators, null bytes, control chars. */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "file";
        return name.replace('\0', '_')
                .replace('/', '_')
                .replace('\\', '_')
                .replaceAll("[\u0000-\u001F]", "_")
                .replaceAll("\\.\\.+", ".")
                .strip();
    }

    private static long parseLong(String value, long defaultValue) {
        if (value == null || value.isBlank()) return defaultValue;
        try { return Long.parseLong(value); } catch (NumberFormatException e) { return defaultValue; }
    }

    static String extensionFromMime(String mimeType) {
        return switch (mimeType) {
            case "image/png" -> ".png";
            case "image/jpeg" -> ".jpg";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            case "image/heic" -> ".heic";
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
            case "video/ogg" -> ".ogv";
            case "video/quicktime" -> ".mov";
            case "video/x-matroska" -> ".mkv";
            case "video/x-msvideo" -> ".avi";
            case "audio/mpeg" -> ".mp3";
            case "audio/ogg" -> ".ogg";
            case "audio/wav" -> ".wav";
            case "audio/webm" -> ".weba";
            case "application/x-tar" -> ".tar";
            default -> "";
        };
    }
}
