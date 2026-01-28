package chat.fray.api;

import chat.fray.db.VoiceStateRepository;
import chat.fray.event.*;
import chat.fray.service.LiveKitService;
import io.livekit.server.WebhookReceiver;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.util.Map;

@Path("/api/v0/webhooks/livekit")
public class LiveKitWebhookResource {

    private static final Logger LOG = Logger.getLogger(LiveKitWebhookResource.class);

    @Inject LiveKitService liveKitService;
    @Inject VoiceStateRepository voiceStateRepo;
    @Inject EventBus eventBus;

    @POST
    @Consumes({"application/webhook+json", "application/json"})
    public Response receive(String body, @Context HttpHeaders headers) {
        if (!liveKitService.isEnabled()) {
            return Response.ok().build();
        }

        String authHeader = headers.getHeaderString("Authorization");
        try {
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
        } catch (Exception e) {
            LOG.warnf("Failed to process LiveKit webhook: %s", e.getMessage());
            return Response.status(400).entity(Map.of("error", "invalid_webhook")).build();
        }

        return Response.ok().build();
    }

    private void handleParticipantJoined(livekit.LivekitWebhook.WebhookEvent event) {
        var room = event.getRoom();
        var participant = event.getParticipant();
        String roomName = room.getName();
        if (!roomName.startsWith("voice-")) return;

        String channelId = roomName.substring("voice-".length());
        String userId = participant.getIdentity();

        // Upsert voice state (may already exist from token endpoint)
        voiceStateRepo.upsert(userId, channelId, 0, 0);
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

    private void publishVoiceStates(String channelId) {
        var states = voiceStateRepo.findByChannel(channelId);
        eventBus.publish(Event.of(EventType.VOICE_STATE_UPDATE,
                Map.of("channel_id", channelId, "voice_states", states),
                Scope.channel(channelId)));
    }
}
