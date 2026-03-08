package chat.kith.api;

import chat.kith.db.VoiceModerationRepository;
import chat.kith.db.VoiceStateRepository;
import chat.kith.event.*;
import chat.kith.service.LiveKitService;
import livekit.LivekitModels;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.livekit.server.WebhookReceiver;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.util.HexFormat;
import java.util.Map;

@Path("/api/v0/webhooks/livekit")
public class LiveKitWebhookResource {

    private static final Logger LOG = Logger.getLogger(LiveKitWebhookResource.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject LiveKitService liveKitService;
    @Inject VoiceStateRepository voiceStateRepo;
    @Inject VoiceModerationRepository voiceModerationRepo;
    @Inject EventBus eventBus;

    @POST
    @Consumes({"application/webhook+json", "application/json"})
    public Response receive(String body, @Context HttpHeaders headers) {
        if (!liveKitService.isEnabled()) {
            return Response.ok().build();
        }

        try {
            if (liveKitService.isManaged()) {
                // Managed mode: validate X-Kith-Central-Secret from central service
                String centralSecret = headers.getHeaderString("X-Kith-Central-Secret");
                String expected = liveKitService.getManagedWebhookSecret();
                if (centralSecret == null || centralSecret.isBlank() || expected == null || !expected.equals(centralSecret)) {
                    LOG.warnf("Managed webhook rejected: invalid or missing X-Kith-Central-Secret");
                    return Response.status(401).entity(Map.of("error", "invalid_secret")).build();
                }
                // Parse the webhook body as JSON and process events
                processManagedWebhook(body);
            } else {
                // Standard mode: validate via LiveKit WebhookReceiver
                String authHeader = headers.getHeaderString("Authorization");
                var receiver = new WebhookReceiver(liveKitService.getApiKey(), liveKitService.getWebhookSecret());
                var event = receiver.receive(body, authHeader);
                String eventType = event.getEvent();
                LOG.debugf("LiveKit webhook: %s", eventType);

                switch (eventType) {
                    case "participant_joined" -> handleParticipantJoined(event);
                    case "participant_left" -> handleParticipantLeft(event);
                    case "room_finished" -> handleRoomFinished(event);
                    default -> LOG.debugf("Unhandled LiveKit webhook event: %s", eventType);
                }
            }
        } catch (Exception e) {
            LOG.warnf("Failed to process LiveKit webhook: %s", e.getMessage());
            return Response.status(400).entity(Map.of("error", "invalid_webhook")).build();
        }

        return Response.ok().build();
    }

    /** Process webhook forwarded from central service (JSON body, not protobuf) */
    private void processManagedWebhook(String body) throws Exception {
        var root = MAPPER.readTree(body);
        String eventType = root.path("event").asText();
        var room = root.path("room");
        String roomName = room.path("name").asText("");

        // Central service uses tenant-prefixed room names: "{account_id}-voice-{channel_id}"
        // Strip the prefix to get the local channel ID
        String channelId = extractChannelId(roomName);
        if (channelId == null) return;

        var participant = root.path("participant");
        String userId = participant.path("identity").asText(null);

        LOG.debugf("Managed webhook: %s room=%s channel=%s user=%s", eventType, roomName, channelId, userId);

        switch (eventType) {
            case "participant_joined" -> {
                if (userId != null) {
                    voiceStateRepo.upsert(userId, channelId, 0, 0);
                    applyPersistentModeration(userId, channelId);
                    publishVoiceStates(channelId);
                }
            }
            case "participant_left" -> {
                if (userId != null) {
                    voiceStateRepo.delete(userId);
                    publishVoiceStates(channelId);
                }
            }
            case "room_finished" -> {
                voiceStateRepo.deleteByChannel(channelId);
                publishVoiceStates(channelId);
            }
        }
    }

    /** Extract channel ID from tenant-prefixed room name: "{account_id}-voice-{channel_id}" */
    private static String extractChannelId(String roomName) {
        int idx = roomName.indexOf("-voice-");
        if (idx < 0) {
            // Not tenant-prefixed, try plain "voice-" prefix
            if (roomName.startsWith("voice-")) return roomName.substring("voice-".length());
            return null;
        }
        return roomName.substring(idx + "-voice-".length());
    }

    private void handleParticipantJoined(livekit.LivekitWebhook.WebhookEvent event) {
        var room = event.getRoom();
        var participant = event.getParticipant();
        String roomName = room.getName();
        if (!roomName.startsWith("voice-")) return;

        String channelId = roomName.substring("voice-".length());
        String userId = participant.getIdentity();

        // Single source of truth — voice state is created when user actually connects
        voiceStateRepo.upsert(userId, channelId, 0, 0);
        applyPersistentModeration(userId, channelId);
        publishVoiceStates(channelId);
    }

    private void handleParticipantLeft(livekit.LivekitWebhook.WebhookEvent event) {
        var room = event.getRoom();
        var participant = event.getParticipant();
        String roomName = room.getName();
        if (!roomName.startsWith("voice-")) return;

        String channelId = roomName.substring("voice-".length());
        String userId = participant.getIdentity();

        voiceStateRepo.delete(userId);
        publishVoiceStates(channelId);
    }

    private void handleRoomFinished(livekit.LivekitWebhook.WebhookEvent event) {
        var room = event.getRoom();
        String roomName = room.getName();
        if (!roomName.startsWith("voice-")) return;

        String channelId = roomName.substring("voice-".length());
        voiceStateRepo.deleteByChannel(channelId);
        publishVoiceStates(channelId);
    }

    /** Re-apply persistent server mute/deafen when a user joins a voice channel */
    private void applyPersistentModeration(String userId, String channelId) {
        var mod = voiceModerationRepo.findByUser(userId);
        if (mod.isEmpty()) return;
        boolean muted = ((Long) mod.get().get("server_mute")).intValue() != 0;
        boolean deaf = ((Long) mod.get().get("server_deaf")).intValue() != 0;
        if (!muted && !deaf) return;

        if (muted) voiceStateRepo.setServerMute(userId, channelId, true);
        if (deaf) voiceStateRepo.setServerDeaf(userId, channelId, true);

        // Best-effort LiveKit enforcement
        String roomName = "voice-" + channelId;
        try {
            if (muted) {
                var participant = liveKitService.getParticipant(roomName, userId);
                for (var track : participant.getTracksList()) {
                    if (track.getType() == LivekitModels.TrackType.AUDIO) {
                        liveKitService.muteTrack(roomName, userId, track.getSid(), true);
                        break;
                    }
                }
            }
            if (deaf) {
                var perm = LivekitModels.ParticipantPermission.newBuilder()
                        .setCanSubscribe(false)
                        .setCanPublish(true)
                        .build();
                liveKitService.updateParticipant(roomName, userId, perm);
            }
        } catch (Exception e) {
            LOG.debugf("LiveKit moderation re-apply for %s: %s", userId, e.getMessage());
        }
    }

    private void publishVoiceStates(String channelId) {
        var states = voiceStateRepo.findByChannel(channelId);
        eventBus.publish(Event.of(EventType.VOICE_STATE_UPDATE,
                Map.of("channel_id", channelId, "voice_states", states),
                Scope.channel(channelId)));
    }
}
