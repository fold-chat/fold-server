package chat.kith.api;

import chat.kith.auth.KithSecurityContext;
import chat.kith.auth.RateLimitFilter;
import chat.kith.auth.RateLimitPolicy;
import chat.kith.auth.RateLimitService;
import chat.kith.db.ChannelRepository;
import chat.kith.db.VoiceKeyRepository;
import chat.kith.db.VoiceStateRepository;
import chat.kith.config.KithLiveKitConfig;
import chat.kith.config.RuntimeConfigService;
import chat.kith.event.*;
import chat.kith.security.Permission;
import chat.kith.security.PermissionService;
import chat.kith.service.LiveKitService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import livekit.LivekitModels;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.*;

@Path("/api/v0/voice")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class VoiceResource {

    private static final Logger LOG = Logger.getLogger(VoiceResource.class);

    @Inject LiveKitService liveKitService;
    @Inject KithLiveKitConfig liveKitConfig;
    @Inject VoiceStateRepository voiceStateRepo;
    @Inject VoiceKeyRepository voiceKeyRepo;
    @Inject ChannelRepository channelRepo;
    @Inject PermissionService permissionService;
    @Inject RateLimitService rateLimitService;
    @Inject RuntimeConfigService runtimeConfig;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

    // --- Join voice / get token ---

    @POST
    @Path("/token")
    public Response token(TokenRequest req) {
        var sc = sc();
        var rl = checkRate("voice_token", sc.getUserId(), RateLimitPolicy.VOICE_TOKEN);
        if (rl != null) return rl;

        if (!liveKitService.isEnabled()) {
            return Response.status(503).entity(Map.of("error", "voice_unavailable", "message", "Voice is not configured")).build();
        }
        if (req == null || req.channel_id() == null) {
            return Response.status(400).entity(Map.of("error", "invalid_request", "message", "channel_id required")).build();
        }

        var channel = channelRepo.findById(req.channel_id());
        if (channel.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "Channel not found")).build();
        }
        if (!"VOICE".equals(channel.get().get("type"))) {
            return Response.status(400).entity(Map.of("error", "not_voice_channel", "message", "Channel is not a voice channel")).build();
        }

        permissionService.requirePermission(sc.getUserId(), req.channel_id(), Permission.USE_VOICE);

        boolean canVideo = permissionService.hasPermission(sc.getUserId(), req.channel_id(), Permission.VIDEO);

        // If user already in another voice channel, leave it first
        var existing = voiceStateRepo.findByUser(sc.getUserId());
        if (existing.isPresent()) {
            String oldChannelId = (String) existing.get().get("channel_id");
            if (!oldChannelId.equals(req.channel_id())) {
                leaveVoiceInternal(sc.getUserId(), oldChannelId);
            }
        }

        // Generate LiveKit token
        String token;
        String livekitUrl;
        try {
            if (liveKitService.isManaged()) {
                var managedResult = liveKitService.generateManagedToken(
                        sc.getUserId(), sc.getUsername(), req.channel_id(), true, true);
                token = managedResult.token();
                livekitUrl = managedResult.url();
            } else {
                token = liveKitService.generateToken(
                        sc.getUserId(), sc.getUsername(), req.channel_id(), true, true);
                livekitUrl = liveKitService.getUrl();
            }
        } catch (LiveKitService.CentralServiceException e) {
            int status = e.statusCode == 429 ? 429 : 503;
            return Response.status(status)
                    .entity(Map.of("error", status == 429 ? "tier_limit" : "voice_unavailable", "message", e.getMessage()))
                    .build();
        }

        // Upsert voice state
        voiceStateRepo.upsert(sc.getUserId(), req.channel_id(), 0, 0);

        // Publish voice state update
        publishVoiceStates(req.channel_id());

        var result = new LinkedHashMap<String, Object>();
        result.put("token", token);
        result.put("url", livekitUrl);
        result.put("can_video", canVideo);

        // E2EE key — only when server-wide E2EE is enabled
        if (runtimeConfig.getBoolean("kith.livekit.e2ee", liveKitConfig.e2ee())) {
            var keyOpt = voiceKeyRepo.getCurrentKey(req.channel_id());
            if (keyOpt.isEmpty()) {
                LOG.warnf("No E2EE key for voice channel %s, generating one", req.channel_id());
                keyOpt = Optional.of(voiceKeyRepo.createKey(req.channel_id()));
            }
            var key = keyOpt.get();
            result.put("encryption_key", Base64.getEncoder().encodeToString((byte[]) key.get("encryption_key")));
            result.put("key_index", key.get("key_index"));
        }

        return Response.ok(result).build();
    }

    // --- Leave voice ---

    @DELETE
    public Response leave() {
        var sc = sc();
        var existing = voiceStateRepo.findByUser(sc.getUserId());
        if (existing.isEmpty()) {
            return Response.noContent().build();
        }
        String channelId = (String) existing.get().get("channel_id");
        leaveVoiceInternal(sc.getUserId(), channelId);
        return Response.noContent().build();
    }

    // --- Update self mute/deaf ---

    @PATCH
    public Response updateState(UpdateStateRequest req) {
        var sc = sc();
        var rl = checkRate("voice_state", sc.getUserId(), RateLimitPolicy.VOICE_STATE);
        if (rl != null) return rl;

        var existing = voiceStateRepo.findByUser(sc.getUserId());
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_in_voice", "message", "Not in a voice channel")).build();
        }
        String channelId = (String) existing.get().get("channel_id");
        int selfMute = req.self_mute() != null ? (req.self_mute() ? 1 : 0) : ((Long) existing.get().get("self_mute")).intValue();
        int selfDeaf = req.self_deaf() != null ? (req.self_deaf() ? 1 : 0) : ((Long) existing.get().get("self_deaf")).intValue();
        voiceStateRepo.upsert(sc.getUserId(), channelId, selfMute, selfDeaf);
        publishVoiceStates(channelId);
        return Response.ok(Map.of("channel_id", channelId, "self_mute", selfMute != 0, "self_deaf", selfDeaf != 0)).build();
    }

    // --- Server mute ---

    @POST
    @Path("/{channelId}/mute/{userId}")
    public Response serverMute(@PathParam("channelId") String channelId, @PathParam("userId") String userId) {
        return doServerMute(channelId, userId, true);
    }

    @POST
    @Path("/{channelId}/unmute/{userId}")
    public Response serverUnmute(@PathParam("channelId") String channelId, @PathParam("userId") String userId) {
        return doServerMute(channelId, userId, false);
    }

    private Response doServerMute(String channelId, String userId, boolean muted) {
        var sc = sc();
        var rl = checkRate("voice_moderation", sc.getUserId(), RateLimitPolicy.VOICE_MODERATION);
        if (rl != null) return rl;
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.MUTE_MEMBERS);

        String roomName = "voice-" + channelId;
        try {
            var participant = liveKitService.getParticipant(roomName, userId);
            // Find audio track
            String audioTrackSid = null;
            for (var track : participant.getTracksList()) {
                if (track.getType() == LivekitModels.TrackType.AUDIO) {
                    audioTrackSid = track.getSid();
                    break;
                }
            }
            if (audioTrackSid != null) {
                liveKitService.muteTrack(roomName, userId, audioTrackSid, muted);
            }
        } catch (IOException e) {
            LOG.warnf("LiveKit muteTrack failed: %s", e.getMessage());
        }

        voiceStateRepo.setServerMute(userId, channelId, muted);
        publishVoiceStates(channelId);
        return Response.ok(Map.of("user_id", userId, "channel_id", channelId, "server_mute", muted)).build();
    }

    // --- Server deafen ---

    @POST
    @Path("/{channelId}/deafen/{userId}")
    public Response serverDeafen(@PathParam("channelId") String channelId, @PathParam("userId") String userId) {
        return doServerDeafen(channelId, userId, true);
    }

    @POST
    @Path("/{channelId}/undeafen/{userId}")
    public Response serverUndeafen(@PathParam("channelId") String channelId, @PathParam("userId") String userId) {
        return doServerDeafen(channelId, userId, false);
    }

    private Response doServerDeafen(String channelId, String userId, boolean deaf) {
        var sc = sc();
        var rl = checkRate("voice_moderation", sc.getUserId(), RateLimitPolicy.VOICE_MODERATION);
        if (rl != null) return rl;
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.DEAFEN_MEMBERS);

        String roomName = "voice-" + channelId;
        try {
            var perm = LivekitModels.ParticipantPermission.newBuilder()
                    .setCanSubscribe(!deaf)
                    .setCanPublish(true)
                    .build();
            liveKitService.updateParticipant(roomName, userId, perm);
        } catch (IOException e) {
            LOG.warnf("LiveKit updateParticipant failed: %s", e.getMessage());
        }

        voiceStateRepo.setServerDeaf(userId, channelId, deaf);
        publishVoiceStates(channelId);
        return Response.ok(Map.of("user_id", userId, "channel_id", channelId, "server_deaf", deaf)).build();
    }

    // --- Force disconnect ---

    @POST
    @Path("/{channelId}/disconnect/{userId}")
    public Response disconnect(@PathParam("channelId") String channelId, @PathParam("userId") String userId) {
        var sc = sc();
        var rl = checkRate("voice_moderation", sc.getUserId(), RateLimitPolicy.VOICE_MODERATION);
        if (rl != null) return rl;
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.MUTE_MEMBERS);

        String roomName = "voice-" + channelId;
        try {
            liveKitService.removeParticipant(roomName, userId);
        } catch (IOException e) {
            LOG.warnf("LiveKit removeParticipant failed: %s", e.getMessage());
        }

        voiceStateRepo.delete(userId);
        publishVoiceStates(channelId);
        return Response.noContent().build();
    }

    // --- Move user between channels ---

    @POST
    @Path("/{channelId}/move/{userId}")
    public Response move(@PathParam("channelId") String channelId, @PathParam("userId") String userId, MoveRequest req) {
        var sc = sc();
        var rl = checkRate("voice_moderation", sc.getUserId(), RateLimitPolicy.VOICE_MODERATION);
        if (rl != null) return rl;
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.MOVE_MEMBERS);

        if (req == null || req.target_channel_id() == null) {
            return Response.status(400).entity(Map.of("error", "invalid_request", "message", "target_channel_id required")).build();
        }

        var target = channelRepo.findById(req.target_channel_id());
        if (target.isEmpty() || !"VOICE".equals(target.get().get("type"))) {
            return Response.status(400).entity(Map.of("error", "invalid_target", "message", "Target must be a voice channel")).build();
        }

        // Remove from old room
        String oldRoom = "voice-" + channelId;
        try {
            liveKitService.removeParticipant(oldRoom, userId);
        } catch (IOException e) {
            LOG.warnf("LiveKit removeParticipant failed during move: %s", e.getMessage());
        }

        // Delete old voice state, upsert new
        voiceStateRepo.delete(userId);
        voiceStateRepo.upsert(userId, req.target_channel_id(), 0, 0);

        // Generate new token for target room
        String moveToken;
        String moveLkUrl;
        try {
            if (liveKitService.isManaged()) {
                var managed = liveKitService.generateManagedToken(userId, userId, req.target_channel_id(), true, true);
                moveToken = managed.token();
                moveLkUrl = managed.url();
            } else {
                moveToken = liveKitService.generateToken(userId, userId, req.target_channel_id(), true, true);
                moveLkUrl = liveKitService.getUrl();
            }
        } catch (LiveKitService.CentralServiceException e) {
            return Response.status(503).entity(Map.of("error", "voice_unavailable", "message", e.getMessage())).build();
        }

        // Publish leave on old channel, join on new
        publishVoiceStates(channelId);
        publishVoiceStates(req.target_channel_id());

        // Send VOICE_MOVE to the moved user
        var moveData = new LinkedHashMap<String, Object>();
        moveData.put("channel_id", req.target_channel_id());
        moveData.put("token", moveToken);
        moveData.put("url", moveLkUrl);

        // E2EE key for target channel — only when enabled
        if (runtimeConfig.getBoolean("kith.livekit.e2ee", liveKitConfig.e2ee())) {
            var keyOpt = voiceKeyRepo.getCurrentKey(req.target_channel_id());
            if (keyOpt.isPresent()) {
                moveData.put("encryption_key", Base64.getEncoder().encodeToString((byte[]) keyOpt.get().get("encryption_key")));
                moveData.put("key_index", keyOpt.get().get("key_index"));
            }
        }
        eventBus.publish(Event.of(EventType.VOICE_MOVE, moveData, Scope.user(userId)));

        return Response.ok(Map.of("user_id", userId, "target_channel_id", req.target_channel_id())).build();
    }

    // --- Rotate E2EE key ---

    @POST
    @Path("/{channelId}/rotate-key")
    public Response rotateKey(@PathParam("channelId") String channelId) {
        if (!runtimeConfig.getBoolean("kith.livekit.e2ee", liveKitConfig.e2ee())) {
            return Response.status(400).entity(Map.of("error", "e2ee_disabled", "message", "E2EE is not enabled")).build();
        }
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_CHANNELS);

        var channel = channelRepo.findById(channelId);
        if (channel.isEmpty() || !"VOICE".equals(channel.get().get("type"))) {
            return Response.status(400).entity(Map.of("error", "not_voice_channel")).build();
        }

        var newKey = voiceKeyRepo.rotateKey(channelId);

        // Publish key rotation event to all users in the channel
        var keyData = new LinkedHashMap<String, Object>();
        keyData.put("channel_id", channelId);
        keyData.put("encryption_key", Base64.getEncoder().encodeToString((byte[]) newKey.get("encryption_key")));
        keyData.put("key_index", newKey.get("key_index"));
        eventBus.publish(Event.of(EventType.VOICE_KEY_ROTATE, keyData, Scope.channel(channelId)));

        return Response.ok(Map.of("channel_id", channelId, "key_index", newKey.get("key_index"))).build();
    }

    // --- Stats (admin only) ---

    @GET
    @Path("/stats")
    public Response stats() {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_SERVER);

        String mode = liveKitService.getMode();
        boolean enabled = liveKitService.isEnabled();

        var result = new LinkedHashMap<String, Object>();
        result.put("mode", mode);
        result.put("status", enabled ? "UP" : "OFF");
        result.put("active_connections", enabled ? voiceStateRepo.countAll() : 0);
        result.put("embedded_binary_available", liveKitService.getEmbeddedBinaryAvailable());

        if (liveKitService.isManaged()) {
            result.put("managed_status", enabled ? "connected" : "disconnected");
        }

        if (enabled) {
            var rooms = liveKitService.listRooms();
            result.put("active_rooms", rooms.size());
            var roomDetails = rooms.stream()
                    .filter(r -> r.getName().startsWith("voice-"))
                    .map(r -> {
                        var rd = new LinkedHashMap<String, Object>();
                        String chId = r.getName().substring("voice-".length());
                        rd.put("channel_id", chId);
                        rd.put("room_name", r.getName());
                        rd.put("participants", r.getNumParticipants());
                        rd.put("num_publishers", r.getNumPublishers());
                        return rd;
                    })
                    .toList();
            result.put("rooms", roomDetails);
        } else {
            result.put("active_rooms", 0);
            result.put("rooms", List.of());
        }

        return Response.ok(result).build();
    }

    // --- DTOs ---

    public record TokenRequest(String channel_id) {}
    public record UpdateStateRequest(Boolean self_mute, Boolean self_deaf) {}
    public record MoveRequest(String target_channel_id) {}

    // --- Helpers ---

    private void leaveVoiceInternal(String userId, String channelId) {
        // Try to remove from LiveKit room
        try {
            liveKitService.removeParticipant("voice-" + channelId, userId);
        } catch (IOException e) {
            LOG.debugf("removeParticipant on leave: %s", e.getMessage());
        }
        voiceStateRepo.delete(userId);
        publishVoiceStates(channelId);
    }

    private void publishVoiceStates(String channelId) {
        var states = voiceStateRepo.findByChannel(channelId);
        eventBus.publish(Event.of(EventType.VOICE_STATE_UPDATE,
                Map.of("channel_id", channelId, "voice_states", states),
                Scope.channel(channelId)));
    }

    private KithSecurityContext sc() {
        return (KithSecurityContext) requestContext.getSecurityContext();
    }

    private Response checkRate(String configKey, String userId, RateLimitPolicy defaultPolicy) {
        var policy = rateLimitService.resolvePolicy(configKey, defaultPolicy);
        var result = rateLimitService.check("user:" + userId + ":" + configKey, policy);
        requestContext.setProperty(RateLimitFilter.RATE_LIMIT_RESULT_KEY, result);
        if (!result.allowed()) {
            return Response.status(429)
                    .entity(Map.of("error", "rate_limited", "retry_after", result.retryAfter().toSeconds()))
                    .build();
        }
        return null;
    }
}
