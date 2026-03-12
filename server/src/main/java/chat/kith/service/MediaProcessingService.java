package chat.kith.service;

import chat.kith.config.KithFileConfig;
import chat.kith.config.KithMediaProcessingConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class MediaProcessingService {

    private static final Logger LOG = Logger.getLogger(MediaProcessingService.class);
    private static final long PROCESS_TIMEOUT_SECONDS = 300;

    // Magic bytes signatures
    private static final byte[] PNG_HEADER = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG_HEADER = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF87_HEADER = "GIF87a".getBytes();
    private static final byte[] GIF89_HEADER = "GIF89a".getBytes();
    private static final byte[] WEBP_RIFF = "RIFF".getBytes();
    private static final byte[] WEBP_WEBP = "WEBP".getBytes();
    // ftyp box for MP4/MOV/M4V
    private static final byte[] FTYP = "ftyp".getBytes();
    // WebM/MKV (EBML header)
    private static final byte[] EBML_HEADER = new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3};
    // AVI
    private static final byte[] AVI_RIFF = "RIFF".getBytes();
    private static final byte[] AVI_AVI = "AVI ".getBytes();

    @Inject KithMediaProcessingConfig mediaConfig;
    @Inject KithFileConfig fileConfig;

    private boolean ffmpegAvailable;
    private boolean ffprobeAvailable;
    private boolean sipsAvailable;
    private boolean imagemagickAvailable;
    private String resolvedEncoder;
    private Semaphore jobSemaphore;
    private Path processingDir;

    @PostConstruct
    void init() {
        jobSemaphore = new Semaphore(mediaConfig.maxConcurrentJobs());
        processingDir = Path.of(fileConfig.dataDir(), "processing").toAbsolutePath();
        try {
            Files.createDirectories(processingDir);
        } catch (IOException e) {
            LOG.warnf("Failed to create processing dir: %s", e.getMessage());
        }

        ffmpegAvailable = probeCommand(mediaConfig.ffmpegPath(), "-version");
        ffprobeAvailable = probeCommand(mediaConfig.ffprobePath(), "-version");
        sipsAvailable = probeCommand("sips", "--help");
        imagemagickAvailable = probeCommand("magick", "--version");

        if (!ffmpegAvailable) {
            LOG.warn("[BOOT] MediaProcessingService: ffmpeg not found — media processing disabled");
        } else {
            resolvedEncoder = detectEncoder();
            LOG.infof("[BOOT] MediaProcessingService ... OK (encoder=%s, ffprobe=%s, sips=%s, magick=%s)",
                    resolvedEncoder, ffprobeAvailable, sipsAvailable, imagemagickAvailable);
        }
    }

    public boolean isFfmpegAvailable() {
        return ffmpegAvailable;
    }

    public boolean canConvertHeic() {
        return sipsAvailable || imagemagickAvailable || ffmpegAvailable;
    }

    /** Return configured video mode from @ConfigMapping. */
    public String getVideoMode() {
        return mediaConfig.videoMode();
    }

    /** Return configured thumbnail max width from @ConfigMapping. */
    public int getDefaultThumbnailMaxWidth() {
        return mediaConfig.thumbnailMaxWidth();
    }

    // --- Magic bytes validation ---

    /** Validate that the file's magic bytes match the claimed MIME type. */
    public boolean validateMagicBytes(Path file, String mimeType) {
        try {
            byte[] header = readHeader(file, 12);
            if (header.length < 3) return false;
            return switch (mimeType) {
                case "image/png" -> startsWith(header, PNG_HEADER);
                case "image/jpeg" -> startsWith(header, JPEG_HEADER);
                case "image/gif" -> startsWith(header, GIF87_HEADER) || startsWith(header, GIF89_HEADER);
                case "image/webp" -> startsWith(header, WEBP_RIFF) && header.length >= 12 && regionMatches(header, 8, WEBP_WEBP);
                case "image/svg+xml" -> isSvgContent(file);
                case "image/heic" -> containsFtyp(header) && isHeicBrand(header);
                case "video/mp4", "video/quicktime" -> containsFtyp(header);
                case "video/webm", "video/x-matroska" -> startsWith(header, EBML_HEADER);
                case "video/x-msvideo" -> startsWith(header, AVI_RIFF) && header.length >= 12 && regionMatches(header, 8, AVI_AVI);
                case "video/ogg" -> startsWith(header, "OggS".getBytes());
                default -> true; // non-media types — skip magic check
            };
        } catch (IOException e) {
            LOG.warnf("Failed to read magic bytes: %s", e.getMessage());
            return false;
        }
    }

    // --- Image processing ---

    /** Convert HEIC to JPEG. Tries sips (macOS) → ImageMagick → ffmpeg. */
    public Optional<Path> convertHeicToJpeg(Path input) {
        var output = processingDir.resolve("heic-" + System.nanoTime() + ".jpg");

        // sips — macOS native, handles tiled HEIC perfectly
        if (sipsAvailable) {
            try {
                int exit = runProcess("sips", "-s", "format", "jpeg", "-s", "formatOptions", "best",
                        input.toString(), "--out", output.toString());
                if (exit == 0 && Files.exists(output)) {
                    // Strip EXIF from converted JPEG
                    var stripped = stripJpegMetadata(output);
                    if (stripped.isPresent()) {
                        Files.deleteIfExists(output);
                        return stripped;
                    }
                    return Optional.of(output);
                }
                Files.deleteIfExists(output);
            } catch (Exception e) {
                LOG.debugf("sips HEIC conversion failed: %s", e.getMessage());
            }
        }

        // ImageMagick — cross-platform, uses libheif
        if (imagemagickAvailable) {
            try {
                int exit = runProcess("magick", input.toString(), "-strip", "-quality", "95",
                        output.toString());
                if (exit == 0 && Files.exists(output)) return Optional.of(output);
                Files.deleteIfExists(output);
            } catch (Exception e) {
                LOG.debugf("ImageMagick HEIC conversion failed: %s", e.getMessage());
            }
        }

        // ffmpeg — last resort
        if (ffmpegAvailable) {
            try {
                int exit = runProcess(mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                        "-map_metadata", "-1", "-q:v", "1", output.toString());
                if (exit == 0 && Files.exists(output)) return Optional.of(output);
                Files.deleteIfExists(output);
            } catch (Exception e) {
                LOG.warnf("ffmpeg HEIC conversion failed: %s", e.getMessage());
            }
        }

        return Optional.empty();
    }

    /** Strip metadata from image, returns path to stripped image. */
    public Optional<Path> stripImageMetadata(Path input, String extension) {
        // Try ffmpeg first
        if (ffmpegAvailable) {
            var output = processingDir.resolve("stripped-" + System.nanoTime() + extension);
            try {
                int exit = runProcess(mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                        "-map_metadata", "-1", "-q:v", "1", output.toString());
                if (exit == 0 && Files.exists(output)) return Optional.of(output);
                Files.deleteIfExists(output);
            } catch (Exception e) {
                LOG.warnf("ffmpeg metadata strip failed, trying Java fallback: %s", e.getMessage());
            }
        }
        // Java fallback for JPEG — strip APP1/APP2 markers (EXIF, XMP) at byte level
        if (".jpg".equals(extension) || ".jpeg".equals(extension)) {
            return stripJpegMetadata(input);
        }
        return Optional.empty();
    }

    /**
     * Pure-Java lossless JPEG metadata stripper. Removes APP1 (EXIF) and APP2 (XMP/ICC) markers.
     * JPEG structure: SOI (FFD8), then marker segments (FFxx + 2-byte length + data), ending with SOS (FFDA) + image data + EOI (FFD9).
     */
    Optional<Path> stripJpegMetadata(Path input) {
        try {
            byte[] data = Files.readAllBytes(input);
            if (data.length < 4 || data[0] != (byte) 0xFF || data[1] != (byte) 0xD8) {
                return Optional.empty(); // not a valid JPEG
            }

            var out = new ByteArrayOutputStream(data.length);
            out.write(0xFF);
            out.write(0xD8); // SOI

            int pos = 2;
            boolean stripped = false;
            while (pos + 1 < data.length) {
                if (data[pos] != (byte) 0xFF) break; // not a marker
                int marker = data[pos + 1] & 0xFF;

                // SOS (0xDA) — rest is image data, copy verbatim
                if (marker == 0xDA) {
                    out.write(data, pos, data.length - pos);
                    break;
                }

                // Markers without length: RST0-RST7 (D0-D7), SOI (D8), EOI (D9)
                if ((marker >= 0xD0 && marker <= 0xD9) || marker == 0x01) {
                    out.write(data, pos, 2);
                    pos += 2;
                    continue;
                }

                // Read marker length
                if (pos + 3 >= data.length) break;
                int len = ((data[pos + 2] & 0xFF) << 8) | (data[pos + 3] & 0xFF);
                int segmentSize = 2 + len; // marker (2) + length-included data

                if (pos + segmentSize > data.length) break;

                // Skip APP1 (0xE1 = EXIF/XMP) and APP2 (0xE2 = ICC/XMP)
                if (marker == 0xE1 || marker == 0xE2) {
                    stripped = true;
                    pos += segmentSize;
                    continue;
                }

                // Copy all other markers
                out.write(data, pos, segmentSize);
                pos += segmentSize;
            }

            if (!stripped) return Optional.empty(); // nothing to strip

            var output = processingDir.resolve("jstrip-" + System.nanoTime() + ".jpg");
            Files.write(output, out.toByteArray());
            return Optional.of(output);
        } catch (IOException e) {
            LOG.warnf("Java JPEG metadata strip failed: %s", e.getMessage());
            return Optional.empty();
        }
    }

    /** Generate thumbnail for image, returns path to thumbnail. */
    public Optional<Path> generateImageThumbnail(Path input) {
        return generateImageThumbnail(input, null);
    }

    /** Generate thumbnail for image. Tries ffmpeg → sips → ImageMagick. */
    public Optional<Path> generateImageThumbnail(Path input, String mimeType) {
        int maxWidth = mediaConfig.thumbnailMaxWidth();
        // Try ffmpeg first
        if (ffmpegAvailable) {
            var output = processingDir.resolve("thumb-" + System.nanoTime() + ".jpg");
            try {
                int exit = runProcess(mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                        "-vf", "scale='min(" + maxWidth + ",iw)':-2",
                        "-q:v", "3", output.toString());
                if (exit == 0 && Files.exists(output)) return Optional.of(output);
                Files.deleteIfExists(output);
            } catch (Exception e) {
                LOG.warnf("ffmpeg thumbnail failed: %s", e.getMessage());
            }
        }
        // sips fallback (macOS)
        if (sipsAvailable) {
            var output = processingDir.resolve("thumb-" + System.nanoTime() + ".jpg");
            try {
                int exit = runProcess("sips", "-s", "format", "jpeg", "-s", "formatOptions", "60",
                        "-Z", String.valueOf(maxWidth), input.toString(), "--out", output.toString());
                if (exit == 0 && Files.exists(output)) return Optional.of(output);
                Files.deleteIfExists(output);
            } catch (Exception e) {
                LOG.warnf("sips thumbnail failed: %s", e.getMessage());
            }
        }
        // ImageMagick fallback
        if (imagemagickAvailable) {
            var output = processingDir.resolve("thumb-" + System.nanoTime() + ".jpg");
            try {
                int exit = runProcess("magick", input.toString(), "-strip", "-resize", maxWidth + "x>",
                        "-quality", "75", output.toString());
                if (exit == 0 && Files.exists(output)) return Optional.of(output);
                Files.deleteIfExists(output);
            } catch (Exception e) {
                LOG.warnf("ImageMagick thumbnail failed: %s", e.getMessage());
            }
        }
        return Optional.empty();
    }

    // --- Video processing ---

    /** Get video duration in seconds via ffprobe. */
    public Optional<Double> getVideoDuration(Path input) {
        if (!ffprobeAvailable) return Optional.empty();
        try {
            var result = runProcessWithOutput(mediaConfig.ffprobePath(), "-nostdin",
                    "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", input.toString());
            if (result.exitCode() == 0 && result.stdout() != null) {
                return Optional.of(Double.parseDouble(result.stdout().trim()));
            }
        } catch (Exception e) {
            LOG.warnf("ffprobe duration failed: %s", e.getMessage());
        }
        return Optional.empty();
    }

    /** Get video dimensions (width, height) via ffprobe. */
    public Optional<int[]> getVideoDimensions(Path input) {
        if (!ffprobeAvailable) return Optional.empty();
        try {
            var result = runProcessWithOutput(mediaConfig.ffprobePath(), "-nostdin",
                    "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=width,height", "-of", "csv=s=x:p=0", input.toString());
            if (result.exitCode() == 0 && result.stdout() != null) {
                var parts = result.stdout().trim().split("x");
                if (parts.length == 2) {
                    return Optional.of(new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])});
                }
            }
        } catch (Exception e) {
            LOG.warnf("ffprobe dimensions failed: %s", e.getMessage());
        }
        return Optional.empty();
    }

    /** Generate thumbnail from video (first frame). */
    public Optional<Path> generateVideoThumbnail(Path input) {
        if (!ffmpegAvailable) return Optional.empty();
        var output = processingDir.resolve("vthumb-" + System.nanoTime() + ".jpg");
        int maxWidth = mediaConfig.thumbnailMaxWidth();
        try {
            int exit = runProcess(mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                    "-vframes", "1", "-vf", "scale='min(" + maxWidth + ",iw)':-2",
                    "-q:v", "3", output.toString());
            if (exit == 0 && Files.exists(output)) return Optional.of(output);
            Files.deleteIfExists(output);
        } catch (Exception e) {
            LOG.warnf("Video thumbnail generation failed: %s", e.getMessage());
        }
        return Optional.empty();
    }


    /** Transcode video to MP4 (H.264 + AAC). Acquires semaphore. */
    public Optional<Path> transcodeVideo(Path input) {
        if (!ffmpegAvailable) return Optional.empty();
        var output = processingDir.resolve("transcode-" + System.nanoTime() + ".mp4");
        try {
            if (!jobSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                LOG.warn("Transcode rejected — max concurrent jobs reached");
                return Optional.empty();
            }
            try {
                String encoder = resolvedEncoder != null ? resolvedEncoder : "libx264";
                var cmd = buildTranscodeCommand(input, output, encoder);
                int exit = runProcess(cmd);
                if (exit == 0 && Files.exists(output)) return Optional.of(output);
                Files.deleteIfExists(output);
            } finally {
                jobSemaphore.release();
            }
        } catch (Exception e) {
            LOG.warnf("Video transcode failed: %s", e.getMessage());
        }
        return Optional.empty();
    }

    public Path getProcessingDir() {
        return processingDir;
    }

    /** Clean up a temp file in the processing directory. */
    public void cleanupTemp(Path file) {
        try {
            if (file != null && file.startsWith(processingDir)) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            LOG.debugf("Failed to clean temp file: %s", e.getMessage());
        }
    }

    // --- Private helpers ---

    private String[] buildTranscodeCommand(Path input, Path output, String encoder) {
        if (encoder.equals("libx264")) {
            return new String[]{mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                    "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                    "-c:a", "aac", "-movflags", "+faststart", output.toString()};
        }
        // HW-accelerated encoder — try with fallback to software
        return switch (encoder) {
            case "h264_videotoolbox" -> new String[]{mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                    "-c:v", "h264_videotoolbox", "-q:v", "65",
                    "-c:a", "aac", "-movflags", "+faststart", output.toString()};
            case "h264_nvenc" -> new String[]{mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                    "-c:v", "h264_nvenc", "-preset", "medium", "-cq", "23",
                    "-c:a", "aac", "-movflags", "+faststart", output.toString()};
            case "h264_vaapi" -> new String[]{mediaConfig.ffmpegPath(), "-nostdin", "-y",
                    "-vaapi_device", "/dev/dri/renderD128",
                    "-i", input.toString(),
                    "-vf", "format=nv12,hwupload",
                    "-c:v", "h264_vaapi", "-qp", "23",
                    "-c:a", "aac", "-movflags", "+faststart", output.toString()};
            case "h264_qsv" -> new String[]{mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                    "-c:v", "h264_qsv", "-preset", "medium", "-global_quality", "23",
                    "-c:a", "aac", "-movflags", "+faststart", output.toString()};
            default -> new String[]{mediaConfig.ffmpegPath(), "-nostdin", "-y", "-i", input.toString(),
                    "-c:v", "libx264", "-preset", "medium", "-crf", "23",
                    "-c:a", "aac", "-movflags", "+faststart", output.toString()};
        };
    }

    private String detectEncoder() {
        String hwAccel = mediaConfig.hwAccel();
        if ("none".equals(hwAccel)) return "libx264";

        if (!"auto".equals(hwAccel)) {
            // Explicit HW encoder requested
            String encoder = "h264_" + hwAccel;
            if (probeEncoder(encoder)) return encoder;
            LOG.warnf("Requested HW encoder %s not available, falling back to libx264", encoder);
            return "libx264";
        }

        // Auto-detect: try platform-specific encoders
        for (String enc : new String[]{"h264_videotoolbox", "h264_nvenc", "h264_vaapi", "h264_qsv"}) {
            if (probeEncoder(enc)) {
                LOG.infof("Auto-detected HW encoder: %s", enc);
                return enc;
            }
        }
        return "libx264";
    }

    private boolean probeEncoder(String encoder) {
        try {
            var result = runProcessWithOutput(mediaConfig.ffmpegPath(), "-nostdin", "-hide_banner",
                    "-encoders");
            return result.exitCode() == 0 && result.stdout() != null && result.stdout().contains(encoder);
        } catch (Exception e) {
            return false;
        }
    }

    private boolean probeCommand(String cmd, String arg) {
        try {
            var pb = new ProcessBuilder(cmd, arg);
            pb.redirectErrorStream(true);
            var p = pb.start();
            p.getInputStream().readAllBytes();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private int runProcess(String... cmd) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        pb.directory(processingDir.toFile());
        var p = pb.start();
        // Read output in background to prevent pipe deadlock, capture for failure logging
        var captured = new java.util.concurrent.atomic.AtomicReference<String>("");
        var reader = Thread.ofVirtual().start(() -> {
            try { captured.set(new String(p.getInputStream().readAllBytes())); } catch (IOException ignored) {}
        });
        if (!p.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            LOG.warnf("Process timed out after %ds: %s", PROCESS_TIMEOUT_SECONDS, String.join(" ", cmd));
            return -1;
        }
        reader.join(5000); // wait for reader to finish
        int exitCode = p.exitValue();
        if (exitCode != 0) {
            String output = captured.get();
            LOG.warnf("Process exited %d: %s — %s", exitCode, String.join(" ", cmd),
                    output.length() > 500 ? output.substring(output.length() - 500) : output);
        }
        return exitCode;
    }

    private record ProcessResult(int exitCode, String stdout) {}

    private ProcessResult runProcessWithOutput(String... cmd) throws IOException, InterruptedException {
        var pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(false);
        pb.directory(processingDir.toFile());
        var p = pb.start();
        // Read stderr in background
        Thread.ofVirtual().start(() -> {
            try { p.getErrorStream().readAllBytes(); } catch (IOException ignored) {}
        });
        String stdout = new String(p.getInputStream().readAllBytes());
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new ProcessResult(-1, null);
        }
        return new ProcessResult(p.exitValue(), stdout);
    }

    private static byte[] readHeader(Path file, int bytes) throws IOException {
        try (InputStream is = Files.newInputStream(file)) {
            return is.readNBytes(bytes);
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) return false;
        return Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }

    private static boolean regionMatches(byte[] data, int offset, byte[] target) {
        if (data.length < offset + target.length) return false;
        return Arrays.equals(data, offset, offset + target.length, target, 0, target.length);
    }

    private static boolean containsFtyp(byte[] header) {
        // ftyp box: bytes 4-7 should be "ftyp"
        return header.length >= 8 && regionMatches(header, 4, FTYP);
    }

    private static boolean isHeicBrand(byte[] header) {
        if (header.length < 12) return false;
        String brand = new String(header, 8, 4);
        return brand.equals("heic") || brand.equals("heix") || brand.equals("mif1") || brand.equals("msf1");
    }

    private static boolean isSvgContent(Path file) throws IOException {
        // Check first 1KB for SVG indicators
        byte[] head = readHeader(file, 1024);
        String content = new String(head).trim().toLowerCase();
        return content.startsWith("<?xml") || content.startsWith("<svg") || content.contains("<svg");
    }

    private static String getSuffix(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot) : "";
    }
}
