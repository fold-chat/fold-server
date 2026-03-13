package chat.fold.service;

import chat.fold.config.FoldLiveKitConfig;
import chat.fold.config.RuntimeConfigService;
import chat.fold.db.VoiceStateRepository;
import chat.fold.livekit.*;
import chat.fold.livekit.LiveKitDto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.rest.client.reactive.QuarkusRestClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class LiveKitService {

    private static final Logger LOG = Logger.getLogger(LiveKitService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    FoldLiveKitConfig config;

    @Inject
    RuntimeConfigService runtimeConfig;

    @Inject
    VoiceStateRepository voiceStateRepo;

    private LiveKitRoomService roomService;
    private HttpClient httpClient;

    // Resolved at runtime — may come from config, EmbeddedLiveKitManager, or central service
    private String apiKey;
    private String apiSecret;
    private String url;

    // Managed mode: last known LiveKit URL from central service response
    private volatile String managedLiveKitUrl;
    private volatile boolean managedAvailable;
    private volatile String managedWebhookSecret;
    private volatile String managedAccountId;

    @PostConstruct
    void init() {
        String mode = getMode();
        if ("off".equals(mode)) {
            LOG.info("[BOOT] LiveKit ... SKIP (mode=off)");
            return;
        }
        if ("managed".equals(mode)) {
            initManaged();
        } else if ("external".equals(mode)) {
            String extUrl = runtimeConfig.getString("fold.livekit.url",
                    config.url().orElse(null));
            String extKey = runtimeConfig.getString("fold.livekit.api-key",
                    config.apiKey().orElse(null));
            String extSecret = runtimeConfig.getString("fold.livekit.api-secret",
                    config.apiSecret().orElse(null));
            if (extUrl == null || extKey == null || extSecret == null) {
                LOG.error("[BOOT] LiveKit (external) ... FAIL (url/key/secret required)");
                return;
            }
            configure(extUrl, extKey, extSecret);
            LOG.info("[BOOT] LiveKit (external) ... OK");
            reconcileOnStartup();
        }
        // embedded mode handled by EmbeddedLiveKitManager
    }

    private void initManaged() {
        String centralUrl = config.centralUrl()
                .orElseThrow(() -> new IllegalStateException("fold.livekit.central-url required for managed mode"));
        String centralApiKey = runtimeConfig.getString("fold.livekit.central-api-key",
                config.centralApiKey().orElse(null));
        if (centralApiKey == null || centralApiKey.isBlank()) {
            LOG.error("[BOOT] LiveKit (managed) ... FAIL (central-api-key required)");
            managedAvailable = false;
            return;
        }

        String webhookUrl = config.webhookUrl().orElse("");
        if (webhookUrl.isBlank()) {
            LOG.error("[BOOT] LiveKit (managed) ... FAIL (fold.livekit.webhook-url required for managed mode)");
            managedAvailable = false;
            return;
        }
        // Strip trailing slashes — central appends the webhook path
        webhookUrl = webhookUrl.replaceAll("/+$", "");

        httpClient = HttpClient.newHttpClient();

        // Register instance with central service
        try {
            var body = MAPPER.writeValueAsString(Map.of(
                    "webhook_url", webhookUrl,
                    "name", "Fold Server",
                    "fold_version", "0.1.0"
            ));
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(centralUrl + "/api/v1/instances/register"))
                    .header("Authorization", "Bearer " + centralApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                var json = MAPPER.readTree(resp.body());
                if (json.has("webhook_secret")) {
                    this.managedWebhookSecret = json.get("webhook_secret").asText();
                }
                if (json.has("account_id")) {
                    this.managedAccountId = json.get("account_id").asText();
                }
                managedAvailable = true;
                // Managed mode can't query LiveKit rooms — clear all stale voice_state
                voiceStateRepo.clearAll();
                LOG.infof("[BOOT] LiveKit (managed) ... OK (webhook_url=%s)", webhookUrl);
            } else if (resp.statusCode() == 403) {
                LOG.error("[BOOT] LiveKit (managed) ... FAILED (invalid API key)");
                managedAvailable = false;
            } else {
                LOG.warnf("[BOOT] LiveKit (managed) ... WARN (central returned %d)", resp.statusCode());
                managedAvailable = false;
            }
        } catch (Exception e) {
            LOG.warnf("[BOOT] LiveKit (managed) ... WARN (central unreachable: %s)", e.getMessage());
            managedAvailable = false;
        }
    }

    /** Reconcile voice_state table with actual LiveKit state on startup */
    public void reconcileOnStartup() {
        if (!isEnabled() || roomService == null) return;
        try {
            voiceStateRepo.clearAll();
            var rooms = listRooms();
            int total = 0;
            for (var room : rooms) {
                if (!room.name().startsWith("voice-")) continue;
                String channelId = room.name().substring("voice-".length());
                var participants = listParticipants(room.name());
                for (var p : participants) {
                    voiceStateRepo.upsert(p.identity(), channelId, 0, 0);
                    total++;
                }
            }
            LOG.infof("[BOOT] Voice state reconciled: %d participants across %d rooms", total, rooms.size());
        } catch (Exception e) {
            LOG.warnf("Voice state reconciliation failed: %s", e.getMessage());
        }
    }

    /** Called by EmbeddedLiveKitManager after process starts, or during init for external mode */
    public void configure(String url, String apiKey, String apiSecret) {
        this.url = url;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        String httpUrl = httpUrl(url);
        this.roomService = QuarkusRestClientBuilder.newBuilder()
                .baseUri(URI.create(httpUrl))
                .register(new LiveKitAuthFilter(apiKey, apiSecret))
                .build(LiveKitRoomService.class);
    }

    /** Effective mode — runtime override first, fallback to @ConfigMapping */
    public String getMode() {
        return runtimeConfig.getString("fold.livekit.mode", config.mode());
    }

    public boolean isEnabled() {
        String mode = getMode();
        if ("managed".equals(mode)) return managedAvailable;
        return !"off".equalsIgnoreCase(mode);
    }

    public boolean isManaged() {
        return "managed".equals(getMode());
    }

    public boolean isExternal() {
        return "external".equals(getMode());
    }

    public String getUrl() {
        if (isManaged()) return managedLiveKitUrl;
        return url;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    /** Get the effective webhook secret (falls back to api secret) */
    public String getWebhookSecret() {
        return config.webhookSecret().filter(s -> !s.isBlank()).orElse(apiSecret);
    }

    /** Webhook secret received from central during managed registration */
    public String getManagedWebhookSecret() {
        return managedWebhookSecret;
    }

    /** Result from managed token generation — includes both token and LiveKit URL */
    public record ManagedTokenResult(String token, String url) {}

    /**
     * Generate a LiveKit access token for a user to join a voice channel.
     * In managed mode, proxies to central service.
     */
    public String generateToken(String userId, String username, String channelId,
                                boolean canPublish, boolean canSubscribe) {
        if (isManaged()) {
            var result = generateManagedToken(userId, username, channelId, canPublish, canSubscribe);
            return result.token();
        }
        String roomName = "voice-" + channelId;
        return new LiveKitToken()
                .identity(userId)
                .name(username)
                .roomJoin(true)
                .room(roomName)
                .canPublish(canPublish)
                .canSubscribe(canSubscribe)
                .toJwt(apiKey, apiSecret);
    }

    /**
     * Generate token via central service. Returns token + LiveKit URL.
     * Throws CentralServiceException on failure.
     */
    public ManagedTokenResult generateManagedToken(String userId, String username, String channelId,
                                                    boolean canPublish, boolean canSubscribe) {
        String centralUrl = config.centralUrl().orElseThrow();
        String centralApiKey = runtimeConfig.getString("fold.livekit.central-api-key",
                config.centralApiKey().orElse(null));
        if (centralApiKey == null) throw new CentralServiceException(500, "central-api-key not configured");
        try {
            var body = MAPPER.writeValueAsString(Map.of(
                    "user_id", userId,
                    "username", username,
                    "channel_id", channelId,
                    "can_publish", canPublish,
                    "can_subscribe", canSubscribe
            ));
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(centralUrl + "/api/v1/voice/token"))
                    .header("Authorization", "Bearer " + centralApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            var json = MAPPER.readTree(resp.body());

            if (resp.statusCode() == 200) {
                String token = json.get("token").asText();
                String lkUrl = json.get("url").asText();
                this.managedLiveKitUrl = lkUrl;
                return new ManagedTokenResult(token, lkUrl);
            } else if (resp.statusCode() == 429) {
                String msg = json.has("message") ? json.get("message").asText() : "Tier limit reached";
                throw new CentralServiceException(429, msg);
            } else {
                String msg = json.has("message") ? json.get("message").asText() : "Central service error";
                throw new CentralServiceException(resp.statusCode(), msg);
            }
        } catch (CentralServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new CentralServiceException(503, "Central service unreachable: " + e.getMessage());
        }
    }

    public static class CentralServiceException extends RuntimeException {
        public final int statusCode;
        public CentralServiceException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /** Mute/unmute a participant's published track */
    public void muteTrack(String roomName, String identity, String trackSid, boolean muted) throws IOException {
        if (isManaged()) {
            String prefixed = managedRoomName(roomName);
            var body = MAPPER.writeValueAsString(Map.of("track_sid", trackSid, "muted", muted));
            centralPost("/api/v1/voice/rooms/" + prefixed + "/participants/" + identity + "/mute", body);
            return;
        }
        roomService.mutePublishedTrack(new MuteTrackRequest(roomName, identity, trackSid, muted));
    }

    /** Remove a participant from a room */
    public void removeParticipant(String roomName, String identity) throws IOException {
        if (isManaged()) {
            String prefixed = managedRoomName(roomName);
            centralDelete("/api/v1/voice/rooms/" + prefixed + "/participants/" + identity);
            return;
        }
        roomService.removeParticipant(new RemoveParticipantRequest(roomName, identity));
    }

    /** Update participant permissions (e.g. canSubscribe=false for server deafen) */
    public void updateParticipant(String roomName, String identity,
                                  ParticipantPermission permission) throws IOException {
        if (isManaged()) {
            String prefixed = managedRoomName(roomName);
            var bodyMap = new java.util.LinkedHashMap<String, Object>();
            bodyMap.put("can_subscribe", permission.canSubscribe());
            bodyMap.put("can_publish", permission.canPublish());
            var body = MAPPER.writeValueAsString(bodyMap);
            centralPatch("/api/v1/voice/rooms/" + prefixed + "/participants/" + identity, body);
            return;
        }
        roomService.updateParticipant(new UpdateParticipantRequest(
                roomName, identity, null, null, permission));
    }

    /** Get a specific participant in a room */
    public ParticipantInfo getParticipant(String roomName, String identity) throws IOException {
        if (isManaged()) {
            String prefixed = managedRoomName(roomName);
            var json = centralGet("/api/v1/voice/rooms/" + prefixed + "/participants/" + identity);
            return MAPPER.treeToValue(json, ParticipantInfo.class);
        }
        return roomService.getParticipant(new GetParticipantRequest(roomName, identity));
    }

    /** List all active rooms */
    public List<Room> listRooms() {
        if (isManaged()) {
            try {
                var json = centralGet("/api/v1/voice/rooms");
                var resp = MAPPER.treeToValue(json, ListRoomsResponse.class);
                return resp != null && resp.rooms() != null ? resp.rooms() : Collections.emptyList();
            } catch (IOException e) {
                LOG.warnf("Failed to list rooms via central: %s", e.getMessage());
                return Collections.emptyList();
            }
        }
        if (roomService == null) return Collections.emptyList();
        try {
            var resp = roomService.listRooms(new ListRoomsRequest());
            return resp != null && resp.rooms() != null ? resp.rooms() : Collections.emptyList();
        } catch (Exception e) {
            LOG.warnf("Failed to list rooms: %s", e.getMessage());
        }
        return Collections.emptyList();
    }

    /** List participants in a specific room */
    public List<ParticipantInfo> listParticipants(String roomName) {
        if (isManaged()) {
            try {
                String prefixed = managedRoomName(roomName);
                var json = centralGet("/api/v1/voice/rooms/" + prefixed + "/participants");
                var resp = MAPPER.treeToValue(json, ListParticipantsResponse.class);
                return resp != null && resp.participants() != null ? resp.participants() : Collections.emptyList();
            } catch (IOException e) {
                LOG.warnf("Failed to list participants via central: %s", e.getMessage());
                return Collections.emptyList();
            }
        }
        if (roomService == null) return Collections.emptyList();
        try {
            var resp = roomService.listParticipants(new RoomParticipantsRequest(roomName));
            return resp != null && resp.participants() != null ? resp.participants() : Collections.emptyList();
        } catch (Exception e) {
            LOG.warnf("Failed to list participants for room %s: %s", roomName, e.getMessage());
        }
        return Collections.emptyList();
    }

    /** Delete a room (used on voice channel deletion) */
    public void deleteRoom(String roomName) {
        if (isManaged() || roomService == null) return;
        try {
            roomService.deleteRoom(new DeleteRoomRequest(roomName));
        } catch (Exception e) {
            LOG.warnf("Failed to delete room %s: %s", roomName, e.getMessage());
        }
    }

    /**
     * Reconfigure LiveKit after runtime config changes (mode, keys, URL).
     * Called by ConfigResource when relevant keys are updated.
     */
    public void reconfigure() {
        String mode = getMode();
        LOG.infof("LiveKit reconfigure: mode=%s", mode);
        switch (mode) {
            case "managed" -> {
                initManaged();
            }
            case "external" -> {
                String extUrl = runtimeConfig.getString("fold.livekit.url",
                        config.url().orElse(null));
                String extKey = runtimeConfig.getString("fold.livekit.api-key",
                        config.apiKey().orElse(null));
                String extSecret = runtimeConfig.getString("fold.livekit.api-secret",
                        config.apiSecret().orElse(null));
                if (extUrl != null && extKey != null && extSecret != null) {
                    configure(extUrl, extKey, extSecret);
                    LOG.info("LiveKit reconfigured (external)");
                } else {
                    LOG.warn("LiveKit external reconfigure skipped — missing url/key/secret");
                }
            }
            case "embedded" -> {
                // EmbeddedLiveKitManager handles its own reconfigure
                LOG.info("LiveKit mode=embedded — EmbeddedLiveKitManager manages lifecycle");
            }
            default -> {
                managedAvailable = false;
                roomService = null;
                LOG.info("LiveKit disabled (mode=off)");
            }
        }
    }

    /** Check if the embedded LiveKit binary is available on disk */
    public boolean getEmbeddedBinaryAvailable() {
        String path = config.path();
        if (path == null || path.isBlank()) return false;
        // Absolute path check
        if (Path.of(path).isAbsolute()) return Files.isExecutable(Path.of(path));
        // Check PATH
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) return false;
        for (String dir : pathEnv.split(System.getProperty("path.separator"))) {
            if (Files.isExecutable(Path.of(dir, path))) return true;
        }
        return false;
    }

    // --- Managed mode helpers ---

    /** Prefix local room name with account_id for central service */
    private String managedRoomName(String roomName) {
        if (managedAccountId == null) throw new IllegalStateException("managedAccountId not set");
        // "voice-{channelId}" → "{accountId}-voice-{channelId}"
        return managedAccountId + "-" + roomName;
    }

    private String centralApiKey() {
        return runtimeConfig.getString("fold.livekit.central-api-key",
                config.centralApiKey().orElse(null));
    }

    private com.fasterxml.jackson.databind.JsonNode centralGet(String path) throws IOException {
        String centralUrl = config.centralUrl().orElseThrow();
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(centralUrl + path))
                    .header("Authorization", "Bearer " + centralApiKey())
                    .GET()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IOException("Central GET " + path + " returned " + resp.statusCode());
            }
            return MAPPER.readTree(resp.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Central request interrupted", e);
        }
    }

    private void centralPost(String path, String body) throws IOException {
        String centralUrl = config.centralUrl().orElseThrow();
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(centralUrl + path))
                    .header("Authorization", "Bearer " + centralApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IOException("Central POST " + path + " returned " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Central request interrupted", e);
        }
    }

    private void centralDelete(String path) throws IOException {
        String centralUrl = config.centralUrl().orElseThrow();
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(centralUrl + path))
                    .header("Authorization", "Bearer " + centralApiKey())
                    .DELETE()
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IOException("Central DELETE " + path + " returned " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Central request interrupted", e);
        }
    }

    private void centralPatch(String path, String body) throws IOException {
        String centralUrl = config.centralUrl().orElseThrow();
        try {
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(centralUrl + path))
                    .header("Authorization", "Bearer " + centralApiKey())
                    .header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                throw new IOException("Central PATCH " + path + " returned " + resp.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Central request interrupted", e);
        }
    }

    /** Convert ws:// or wss:// URL to http:// or https:// for API calls */
    private static String httpUrl(String wsUrl) {
        if (wsUrl == null) return null;
        return wsUrl.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
    }
}
