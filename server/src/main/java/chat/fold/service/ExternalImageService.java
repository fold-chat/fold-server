package chat.fold.service;

import chat.fold.config.FoldFileConfig;
import chat.fold.config.FoldMediaConfig;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class ExternalImageService {

    private static final Logger LOG = Logger.getLogger(ExternalImageService.class);

    // Matches ![alt](url) but not already-proxied /api/ URLs or ![GIF](...) patterns
    private static final Pattern MD_IMAGE_RE = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");
    private static final Pattern GIF_PATTERN = Pattern.compile("^!\\[GIF\\]\\(/api/v0/media/proxy\\?url=.+\\)$", Pattern.MULTILINE);

    @Inject FoldMediaConfig mediaConfig;
    @Inject FoldFileConfig fileConfig;
    @Inject MediaProcessingService mediaProcessingService;

    private Path cacheDir;
    private volatile HttpClient httpClient;

    @PostConstruct
    void init() {
        cacheDir = Path.of(fileConfig.dataDir(), "cache", "images").toAbsolutePath();
        try {
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            LOG.warn("Failed to create image cache directory", e);
        }
    }

    private HttpClient httpClient() {
        if (httpClient == null) {
            httpClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
        }
        return httpClient;
    }

    /**
     * Process message content: find external markdown images, download+cache them,
     * rewrite URLs to local proxy endpoint with dimensions.
     */
    public String processContent(String content) {
        if (content == null || content.isBlank()) return content;

        // Skip pure GIF messages
        if (GIF_PATTERN.matcher(content.trim()).matches()) return content;

        int maxImages = mediaConfig.maxImagesPerMessage();

        // Single pass: collect external image matches
        Matcher m = MD_IMAGE_RE.matcher(content);
        List<MatchData> matches = new ArrayList<>();
        while (m.find()) {
            String url = m.group(2);
            if (url.startsWith("/api/")) continue;
            if (m.group(0).startsWith("![GIF](")) continue;
            matches.add(new MatchData(m.start(), m.end(), m.group(1), url));
        }

        if (matches.isEmpty()) return content;

        // Process up to maxImages, strip the rest
        StringBuilder result = new StringBuilder();
        int lastEnd = 0;
        int processed = 0;

        for (MatchData md : matches) {
            result.append(content, lastEnd, md.start);
            processed++;

            if (processed > maxImages) {
                // Strip: convert ![alt](url) to [alt](url) (plain link)
                result.append("[").append(md.alt).append("](").append(md.url).append(")");
            } else {
                String rewritten = processExternalImage(md.alt, md.url);
                result.append(rewritten);
            }
            lastEnd = md.end;
        }
        result.append(content, lastEnd, content.length());

        return result.toString();
    }

    /**
     * Download, cache, and rewrite a single external image.
     * Returns rewritten markdown or degraded link on failure.
     */
    private String processExternalImage(String alt, String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme())) {
                LOG.debugf("Rejecting non-HTTPS image URL: %s", url);
                return "[" + alt + "](" + url + ")";
            }
            if (isBlockedHost(url)) {
                LOG.debugf("Rejecting blocked host image URL: %s", url);
                return "[" + alt + "](" + url + ")";
            }

            String urlHash = hash(url);
            Path cachedFile = cacheDir.resolve(urlHash);
            Path ctFile = cacheDir.resolve(urlHash + ".ct");

            if (!Files.exists(cachedFile)) {
                byte[] data = downloadImage(url);
                if (data == null) return "[" + alt + "](" + url + ")";
                Files.write(cachedFile, data);
                Files.writeString(ctFile, detectContentType(data));
            }

            // Read dimensions once from cached file, reuse for URL and thumbnail decision
            var dims = mediaProcessingService.getImageDimensions(cachedFile).orElse(null);
            ensureThumbnail(urlHash, cachedFile);
            return buildRewrittenMarkdown(alt, urlHash, dims);

        } catch (Exception e) {
            LOG.debugf("Failed to process external image %s: %s", url, e.getMessage());
            return "[" + alt + "](" + url + ")";
        }
    }

    private String buildRewrittenMarkdown(String alt, String hash, int[] dims) {
        if (dims != null) {
            return "![" + alt + "](/api/v0/images/" + hash + "?thumb=true&w=" + dims[0] + "&h=" + dims[1] + ")";
        }
        return "![" + alt + "](/api/v0/images/" + hash + "?thumb=true)";
    }

    /** Generate and cache thumbnail for an external image. No-op if already exists or generation fails. */
    private void ensureThumbnail(String hash, Path cachedFile) {
        var thumbFile = cacheDir.resolve(hash + "_thumb");
        if (Files.exists(thumbFile)) return;
        try {
            var thumbPath = mediaProcessingService.generateImageThumbnail(cachedFile, null, 300);
            if (thumbPath.isPresent()) {
                Files.move(thumbPath.get(), thumbFile);
                Files.writeString(cacheDir.resolve(hash + "_thumb.ct"), "image/jpeg");
            }
        } catch (Exception e) {
            LOG.debugf("Thumbnail generation failed for cached image %s: %s", hash, e.getMessage());
        }
    }

    /**
     * Stream-download image with size limit. Returns null on failure.
     */
    private byte[] downloadImage(String url) {
        long maxSize = mediaConfig.maxProxyImageSize();
        try {
            // Follow redirects manually with SSRF checks (max 3 hops)
            String fetchUrl = url;
            for (int hop = 0; hop < 3; hop++) {
                var request = HttpRequest.newBuilder()
                        .uri(URI.create(fetchUrl))
                        .timeout(Duration.ofSeconds(15))
                        .GET()
                        .build();
                var response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (status >= 300 && status < 400) {
                    var location = response.headers().firstValue("Location").orElse(null);
                    if (location == null) return null;
                    if (isBlockedHost(location)) return null;
                    URI redirectUri = URI.create(fetchUrl).resolve(location);
                    if (!"https".equals(redirectUri.getScheme())) return null;
                    fetchUrl = redirectUri.toString();
                    response.body().close();
                    continue;
                }

                if (status != 200) {
                    response.body().close();
                    return null;
                }

                // Check Content-Length header first
                var contentLength = response.headers().firstValueAsLong("Content-Length");
                if (contentLength.isPresent() && contentLength.getAsLong() > maxSize) {
                    response.body().close();
                    LOG.debugf("Image too large (Content-Length: %d): %s", contentLength.getAsLong(), url);
                    return null;
                }

                // Check Content-Type
                var contentType = response.headers().firstValue("Content-Type").orElse("");
                if (!contentType.startsWith("image/")) {
                    response.body().close();
                    LOG.debugf("Not an image (Content-Type: %s): %s", contentType, url);
                    return null;
                }

                // Stream read with size limit
                try (InputStream is = response.body()) {
                    byte[] buf = new byte[8192];
                    var out = new java.io.ByteArrayOutputStream();
                    long total = 0;
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        total += n;
                        if (total > maxSize) {
                            LOG.debugf("Image download aborted (exceeded %d bytes): %s", maxSize, url);
                            return null;
                        }
                        out.write(buf, 0, n);
                    }
                    return out.toByteArray();
                }
            }
            return null; // Exceeded redirect limit
        } catch (Exception e) {
            LOG.debugf("Image download failed for %s: %s", url, e.getMessage());
            return null;
        }
    }

    /** Detect content type from magic bytes. */
    private String detectContentType(byte[] data) {
        if (data.length >= 8) {
            if (data[0] == (byte) 0x89 && data[1] == 0x50 && data[2] == 0x4E && data[3] == 0x47) return "image/png";
            if (data[0] == (byte) 0xFF && data[1] == (byte) 0xD8 && data[2] == (byte) 0xFF) return "image/jpeg";
            if (data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "image/gif";
            if (data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                    && data.length >= 12 && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
                return "image/webp";
            }
        }
        return "application/octet-stream";
    }

    /**
     * SSRF protection — blocks private/reserved IPs.
     * Reused logic from MediaProxyResource.isBlockedHost().
     */
    private boolean isBlockedHost(String urlStr) {
        try {
            var host = URI.create(urlStr).getHost();
            if (host == null) return true;
            for (var addr : InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                        || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()
                        || addr.isMulticastAddress()) {
                    return true;
                }
                if (addr instanceof java.net.Inet4Address) {
                    int ip = ((addr.getAddress()[0] & 0xFF) << 24)
                           | ((addr.getAddress()[1] & 0xFF) << 16)
                           | ((addr.getAddress()[2] & 0xFF) << 8)
                           |  (addr.getAddress()[3] & 0xFF);
                    if ((ip & 0xFFC00000) == 0x64400000) return true; // CGNAT
                    if ((ip & 0xFF000000) == 0) return true; // 0.0.0.0/8
                }
            }
            return false;
        } catch (Exception e) {
            return true;
        }
    }

    /** Serve a cached image by hash. Returns empty if not found. */
    public Optional<CachedImage> getCachedImage(String hash, boolean thumb) {
        // Validate hash (hex chars only, prevent path traversal)
        if (!hash.matches("[a-f0-9]{64}")) return Optional.empty();

        // Try thumbnail first if requested
        if (thumb) {
            var thumbFile = cacheDir.resolve(hash + "_thumb");
            var thumbCt = cacheDir.resolve(hash + "_thumb.ct");
            try {
                if (Files.exists(thumbFile) && Files.exists(thumbCt)) {
                    return Optional.of(new CachedImage(Files.readAllBytes(thumbFile), Files.readString(thumbCt).trim()));
                }
            } catch (IOException e) {
                LOG.debug("Failed to read cached thumbnail: " + hash, e);
            }
            // Fall through to full image
        }

        var dataFile = cacheDir.resolve(hash);
        var ctFile = cacheDir.resolve(hash + ".ct");
        try {
            if (Files.exists(dataFile) && Files.exists(ctFile)) {
                var contentType = Files.readString(ctFile).trim();
                return Optional.of(new CachedImage(Files.readAllBytes(dataFile), contentType));
            }
        } catch (IOException e) {
            LOG.debug("Failed to read cached image: " + hash, e);
        }
        return Optional.empty();
    }

    static String hash(String url) {
        try {
            var md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public record CachedImage(byte[] data, String contentType) {}
    private record MatchData(int start, int end, String alt, String url) {}
}
