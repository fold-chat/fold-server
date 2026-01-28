package chat.fray.service;

import chat.fray.config.FrayLiveKitConfig;
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
import java.util.Collections;
import java.util.List;

@ApplicationScoped
public class LiveKitService {

    private static final Logger LOG = Logger.getLogger(LiveKitService.class);

    @Inject
    FrayLiveKitConfig config;

    private RoomServiceClient roomService;

    // Resolved at runtime — may come from config or EmbeddedLiveKitManager
    private String apiKey;
    private String apiSecret;
    private String url;

    @PostConstruct
    void init() {
        if (!isEnabled()) {
            LOG.info("[BOOT] LiveKit ... SKIP (not configured)");
            return;
        }
        // Credentials are set by configure() — called by EmbeddedLiveKitManager or on startup for external mode
        if ("external".equals(config.mode())) {
            configure(
                    config.url().orElseThrow(() -> new IllegalStateException("fray.livekit.url required for external mode")),
                    config.apiKey().orElseThrow(() -> new IllegalStateException("fray.livekit.api-key required for external mode")),
                    config.apiSecret().orElseThrow(() -> new IllegalStateException("fray.livekit.api-secret required for external mode"))
            );
            LOG.info("[BOOT] LiveKit (external) ... OK");
        }
    }

    /** Called by EmbeddedLiveKitManager after process starts, or during init for external mode */
    public void configure(String url, String apiKey, String apiSecret) {
        this.url = url;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.roomService = RoomServiceClient.createClient(httpUrl(url), apiKey, apiSecret);
    }

    public boolean isEnabled() {
        return !"off".equalsIgnoreCase(config.mode());
    }

    public String getUrl() {
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

    /**
     * Generate a LiveKit access token for a user to join a voice channel.
     *
     * @param userId       participant identity
     * @param username     participant display name
     * @param channelId    maps to room name "voice-{channelId}"
     * @param canPublish   whether participant can publish tracks
     * @param canSubscribe whether participant can subscribe to tracks
     * @return signed JWT token string
     */
    public String generateToken(String userId, String username, String channelId,
                                boolean canPublish, boolean canSubscribe) {
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

    /** Mute/unmute a participant's published track */
    public void muteTrack(String roomName, String identity, String trackSid, boolean muted) throws IOException {
        var call = roomService.mutePublishedTrack(roomName, identity, trackSid, muted);
        var response = call.execute();
        if (!response.isSuccessful()) {
            throw new IOException("muteTrack failed: " + response.code());
        }
    }

    /** Remove a participant from a room */
    public void removeParticipant(String roomName, String identity) throws IOException {
        var call = roomService.removeParticipant(roomName, identity);
        var response = call.execute();
        if (!response.isSuccessful()) {
            throw new IOException("removeParticipant failed: " + response.code());
        }
    }

    /** Update participant permissions (e.g. canSubscribe=false for server deafen) */
    public void updateParticipant(String roomName, String identity,
                                  LivekitModels.ParticipantPermission permission) throws IOException {
        var call = roomService.updateParticipant(roomName, identity, null, null, permission);
        var response = call.execute();
        if (!response.isSuccessful()) {
            throw new IOException("updateParticipant failed: " + response.code());
        }
    }

    /** Get a specific participant in a room */
    public LivekitModels.ParticipantInfo getParticipant(String roomName, String identity) throws IOException {
        var call = roomService.getParticipant(roomName, identity);
        var response = call.execute();
        if (!response.isSuccessful()) {
            throw new IOException("getParticipant failed: " + response.code());
        }
        return response.body();
    }

    /** List all active rooms */
    public List<LivekitModels.Room> listRooms() {
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
        try {
            var call = roomService.deleteRoom(roomName);
            call.execute();
        } catch (IOException e) {
            LOG.warnf("Failed to delete room %s: %s", roomName, e.getMessage());
        }
    }

    /** Convert ws:// or wss:// URL to http:// or https:// for API calls */
    private static String httpUrl(String wsUrl) {
        if (wsUrl == null) return null;
        return wsUrl.replaceFirst("^wss://", "https://").replaceFirst("^ws://", "http://");
    }
}
