package chat.kith.api;

import chat.kith.auth.KithSecurityContext;
import chat.kith.auth.RateLimitFilter;
import chat.kith.auth.RateLimitPolicy;
import chat.kith.auth.RateLimitService;
import chat.kith.config.KithMediaConfig;
import chat.kith.service.MediaCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

@Path("/api/v0/media")
@Produces(MediaType.APPLICATION_JSON)
public class MediaProxyResource {

    private static final String KLIPY_BASE = "https://api.klipy.com/v2";
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private static final Set<String> ALLOWED_DOMAINS = Set.of(
            "media.klipy.com", "klipy.com"
    );

    @Inject KithMediaConfig mediaConfig;
    @Inject RateLimitService rateLimitService;
    @Inject MediaCacheService mediaCacheService;
    @Context ContainerRequestContext requestContext;

    private final ObjectMapper mapper = new ObjectMapper();

    @GET
    @Path("/search")
    public Response search(
            @QueryParam("q") String query,
            @QueryParam("type") @DefaultValue("gif") String type,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("pos") String pos
    ) {
        var apiKey = mediaConfig.klipyApiKey().filter(s -> !s.isBlank());
        if (apiKey.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "media_search_disabled")).build();
        }

        var rl = checkRate();
        if (rl != null) return rl;

        if (query == null || query.isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_query", "message", "Query parameter 'q' is required")).build();
        }

        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50;

        var sb = new StringBuilder(KLIPY_BASE).append("/search?key=")
                .append(enc(apiKey.get()))
                .append("&q=").append(enc(query))
                .append("&limit=").append(limit)
                .append("&media_filter=").append(mediaFilter(type));
        if (pos != null && !pos.isBlank()) sb.append("&pos=").append(enc(pos));

        return proxyRequest(sb.toString());
    }

    @GET
    @Path("/trending")
    public Response trending(
            @QueryParam("type") @DefaultValue("gif") String type,
            @QueryParam("limit") @DefaultValue("20") int limit,
            @QueryParam("pos") String pos
    ) {
        var apiKey = mediaConfig.klipyApiKey().filter(s -> !s.isBlank());
        if (apiKey.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "media_search_disabled")).build();
        }

        var rl = checkRate();
        if (rl != null) return rl;

        if (limit < 1) limit = 1;
        if (limit > 50) limit = 50;

        var sb = new StringBuilder(KLIPY_BASE).append("/featured?key=")
                .append(enc(apiKey.get()))
                .append("&limit=").append(limit)
                .append("&media_filter=").append(mediaFilter(type));
        if (pos != null && !pos.isBlank()) sb.append("&pos=").append(enc(pos));

        return proxyRequest(sb.toString());
    }

    @GET
    @Path("/proxy")
    @Produces(MediaType.WILDCARD)
    public Response proxy(@QueryParam("url") String url) {
        if (url == null || url.isBlank()) {
            return Response.status(400).entity(Map.of("error", "missing_url")).build();
        }
        if (!isAllowedDomain(url)) {
            return Response.status(403).entity(Map.of("error", "forbidden_domain")).build();
        }

        // Check cache
        var cached = mediaCacheService.get(url);
        if (cached.isPresent()) {
            return Response.ok(cached.get().data())
                    .type(cached.get().contentType())
                    .header("Cache-Control", "public, max-age=86400")
                    .build();
        }

        // Fetch from upstream
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                return Response.status(502).build();
            }
            var contentType = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
            var data = response.body();
            mediaCacheService.put(url, data, contentType);
            return Response.ok(data)
                    .type(contentType)
                    .header("Cache-Control", "public, max-age=86400")
                    .build();
        } catch (Exception e) {
            return Response.status(502).build();
        }
    }

    private boolean isAllowedDomain(String urlStr) {
        try {
            var host = URI.create(urlStr).getHost();
            if (host == null) return false;
            return ALLOWED_DOMAINS.stream().anyMatch(d -> host.equals(d) || host.endsWith("." + d));
        } catch (Exception e) {
            return false;
        }
    }

    private Response proxyRequest(String url) {
        try {
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            var response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return Response.status(502).entity(Map.of("error", "upstream_error")).build();
            }

            var body = mapper.readTree(response.body());
            return Response.ok(normalizeResponse(body)).build();
        } catch (Exception e) {
            return Response.status(502).entity(Map.of("error", "upstream_error")).build();
        }
    }

    /**
     * Normalize Klipy/Tenor v2 response into our simplified format.
     * Tenor v2 results have: id, title, media_formats.{gif,tinygif,...}.{url,dims[w,h],size}
     */
    private Map<String, Object> normalizeResponse(JsonNode body) {
        var results = new ArrayList<Map<String, Object>>();
        var resultsNode = body.path("results");

        if (resultsNode.isArray()) {
            for (var item : resultsNode) {
                // Skip ads
                if (item.has("ad") || item.path("type").asText("").equals("ad")) continue;

                var normalized = new LinkedHashMap<String, Object>();
                normalized.put("id", item.path("id").asText());
                normalized.put("title", item.path("content_description").asText(item.path("title").asText("")));

                // Prefer gif format for url, tinygif for preview
                var mediaFormats = item.path("media_formats");
                var gif = mediaFormats.path("gif");
                var tinygif = mediaFormats.path("tinygif");

                if (gif.isMissingNode()) gif = mediaFormats.path("mp4");
                if (tinygif.isMissingNode()) tinygif = mediaFormats.path("nanogif");
                if (tinygif.isMissingNode()) tinygif = gif;

                normalized.put("url", proxyUrl(gif.path("url").asText("")));
                normalized.put("preview_url", proxyUrl(tinygif.path("url").asText("")));

                var dims = gif.path("dims");
                if (dims.isArray() && dims.size() >= 2) {
                    normalized.put("width", dims.get(0).asInt());
                    normalized.put("height", dims.get(1).asInt());
                } else {
                    normalized.put("width", 0);
                    normalized.put("height", 0);
                }

                results.add(normalized);
            }
        }

        var out = new LinkedHashMap<String, Object>();
        out.put("results", results);
        out.put("next", body.path("next").asText(null));
        return out;
    }

    private String proxyUrl(String originalUrl) {
        if (originalUrl.isEmpty()) return "";
        return "/api/v0/media/proxy?url=" + enc(originalUrl);
    }

    private String mediaFilter(String type) {
        return "sticker".equals(type) ? "tinygif,gif" : "gif,tinygif";
    }

    private String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private KithSecurityContext sc() {
        return (KithSecurityContext) requestContext.getSecurityContext();
    }

    private Response checkRate() {
        var userId = sc().getUserId();
        var policy = rateLimitService.resolvePolicy("media_search", RateLimitPolicy.MEDIA_SEARCH);
        var result = rateLimitService.check("user:" + userId + ":media_search", policy);
        requestContext.setProperty(RateLimitFilter.RATE_LIMIT_RESULT_KEY, result);
        if (!result.allowed()) {
            return Response.status(429)
                    .entity(Map.of("error", "rate_limited", "retry_after", result.retryAfter().toSeconds()))
                    .build();
        }
        return null;
    }
}
