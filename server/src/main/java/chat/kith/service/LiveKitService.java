package chat.kith.service;

import chat.kith.config.KithLiveKitConfig;
import chat.kith.config.RuntimeConfigService;
import chat.kith.db.VoiceStateRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import io.livekit.server.CanPublish;
import io.livekit.server.CanSubscribe;
import io.livekit.server.RoomServiceClient;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import livekit.LivekitModels;
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
    KithLiveKitConfig config;

    @Inject
    RuntimeConfigService runtimeConfig;

    @Inject
    VoiceStateRepository voiceStateRepo;

    private RoomServiceClient roomService;
    private HttpClient httpClient;

    // Resolved at runtime — may come from config, EmbeddedLiveKitManager, or central service
    private String apiKey;
    private String apiSecret;
    private String url;

    // Managed mode: last known LiveKit URL from central service response
    private volatile String managedLiveKitUrl;
    private volatile boolean managedAvailable;

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
            String extUrl = runtimeConfig.getString("kith.livekit.url",
                    config.url().orElse(null));
            String extKey = runtimeConfig.getString("kith.livekit.api-key",
                    config.apiKey().orElse(null));
            String extSecret = runtimeConfig.getString("kith.livekit.api-secret",
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
                .orElseThrow(() -> new IllegalStateException("kith.livekit.central-url required for managed mode"));
        String centralApiKey = runtimeConfig.getString("kith.livekit.central-api-key",
                config.centralApiKey().orElse(null));
        if (centralApiKey == null || centralApiKey.isBlank()) {
            LOG.error("[BOOT] LiveKit (managed) ... FAIL (central-api-key required)");
            managedAvailable = false;
            return;
        }
        httpClient = HttpClient.newHttpClient();

        // Register instance with central service
        try {
            var body = MAPPER.writeValueAsString(Map.of(
                    "webhook_url", "", // TODO: set when public URL is known
                    "name", "Kith Server",
                    "kith_version", "0.1.0"
            ));
            var req = HttpRequest.newBuilder()
                    .uri(URI.create(centralUrl + "/api/v1/instances/register"))
                    .header("Authorization", "Bearer " + centralApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                managedAvailable = true;
                // Managed mode can't query LiveKit rooms — clear all stale voice_state
                voiceStateRepo.clearAll();
                LOG.info("[BOOT] LiveKit (managed) ... OK");
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
                if (!room.getName().startsWith("voice-")) continue;
                String channelId = room.getName().substring("voice-".length());
                var participants = listParticipants(room.getName());
                for (var p : participants) {
                    voiceStateRepo.upsert(p.getIdentity(), channelId, 0, 0);
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
        this.roomService = RoomServiceClient.createClient(httpUrl(url), apiKey, apiSecret);
    }

    /** Effective mode — runtime override first, fallback to @ConfigMapping */
    public String getMode() {
        return runtimeConfig.getString("kith.livekit.mode", config.mode());
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
        AccessToken token = new AccessToken(apiKey, apiSecret);
        token.setIdentity(userId);
        token.setName(username);
        token.addGrants(
                new RoomJoin(true),
                new RoomName(roomName),
                new CanPublish(canPublish),
                new CanSubscribe(canSubscribe)
        );
        return token.toJwt();
    }

    /**
     * Generate token via central service. Returns token + LiveKit URL.
     * Throws CentralServiceException on failure.
     */
    public ManagedTokenResult generateManagedToken(String userId, String username, String channelId,
                                                    boolean canPublish, boolean canSubscribe) {
        String centralUrl = config.centralUrl().orElseThrow();
        String centralApiKey = runtimeConfig.getString("kith.livekit.central-api-key",
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
            LOG.debugf("muteTrack skipped in managed mode (Phase 4 will proxy via central)");
            return;
        }
        var call = roomService.mutePublishedTrack(roomName, identity, trackSid, muted);
        var response = call.execute();
        if (!response.isSuccessful()) {
            throw new IOException("muteTrack failed: " + response.code());
        }
    }

    /** Remove a participant from a room */
    public void removeParticipant(String roomName, String identity) throws IOException {
        if (isManaged()) {
            LOG.debugf("removeParticipant skipped in managed mode (Phase 4 will proxy via central)");
            return;
        }
        var call = roomService.removeParticipant(roomName, identity);
        var response = call.execute();
        if (!response.isSuccessful()) {
            throw new IOException("removeParticipant failed: " + response.code());
        }
    }

    /** Update participant permissions (e.g. canSubscribe=false for server deafen) */
    public void updateParticipant(String roomName, String identity,
                                  LivekitModels.ParticipantPermission permission) throws IOException {
        if (isManaged()) {
            LOG.debugf("updateParticipant skipped in managed mode (Phase 4 will proxy via central)");
            return;
        }
        var call = roomService.updateParticipant(roomName, identity, null, null, permission);
        var response = call.execute();
        if (!response.isSuccessful()) {
            throw new IOException("updateParticipant failed: " + response.code());
        }
    }

    /** Get a specific participant in a room */
    public LivekitModels.ParticipantInfo getParticipant(String roomName, String identity) throws IOException {
        if (isManaged()) {
            throw new IOException("getParticipant not available in managed mode (Phase 4 will proxy via central)");
        }
        var call = roomService.getParticipant(roomName, identity);
        var response = call.execute();
        if (!response.isSuccessful()) {
            throw new IOException("getParticipant failed: " + response.code());
        }
        return response.body();
    }

    /** List all active rooms */
    public List<LivekitModels.Room> listRooms() {
        if (isManaged() || roomService == null) return Collections.emptyList();
        try {
            var call = roomService.listRooms();
            var response = call.execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body();
            }
        } catch (IOException e) {
            LOG.warnf("Failed to list rooms: %s", e.getMessage());
        }
        return Collections.emptyList();
    }

    /** List participants in a specific room */
    public List<LivekitModels.ParticipantInfo> listParticipants(String roomName) {
        if (isManaged() || roomService == null) return Collections.emptyList();
        try {
            var call = roomService.listParticipants(roomName);
            var response = call.execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body();
            }
        } catch (IOException e) {
            LOG.warnf("Failed to list participants for room %s: %s", roomName, e.getMessage());
        }
        return Collections.emptyList();
    }

    /** Delete a room (used on voice channel deletion) */
    public void deleteRoom(String roomName) {
        if (isManaged() || roomService == null) return;
        try {
            var call = roomService.deleteRoom(roomName);
            call.execute();
        } catch (IOException e) {
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
                String extUrl = runtimeConfig.getString("kith.livekit.url",
                        config.url().orElse(null));
                String extKey = runtimeConfig.getString("kith.livekit.api-key",
                        config.apiKey().orElse(null));
                String extSecret = runtimeConfig.getString("kith.livekit.api-secret",
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

    /** Convert ws:// or wss:// URL to http:// or https:// for API calls */
    private static String httpUrl(String wsUrl) {
        if (wsUrl == null) return null;
        return wsUrl.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
    }
}
