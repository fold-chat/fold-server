package chat.kith.api;

import chat.kith.db.VoiceModerationRepository;
import chat.kith.db.VoiceStateRepository;
import chat.kith.event.*;
import chat.kith.livekit.LiveKitDto;
import chat.kith.livekit.LiveKitDto.*;
import chat.kith.service.LiveKitService;
import io.jsonwebtoken.Jwts;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

@Path("/api/v0/webhooks/livekit")
public class LiveKitWebhookResource {

    private static final Logger LOG = Logger.getLogger(LiveKitWebhookResource.class);

    @Inject LiveKitService liveKitService;
    @Inject VoiceStateRepository voiceStateRepo;
    @Inject VoiceModerationRepository voiceModerationRepo;
    @Inject EventBus eventBus;

    @POST
    @Consumes({"application/webhook+json", "application/json"})
    public Response receive(WebhookEvent event, @Context RoutingContext rc, @Context HttpHeaders headers) {
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
            } else {
                // Standard mode: validate JWT against raw body hash
                String rawBody = rc.body() != null ? rc.body().asString() : null;
                if (rawBody == null || !validateWebhookJwt(rawBody, headers)) {
                    return Response.status(401).entity(Map.of("error", "invalid_webhook")).build();
                }
            }

            processEvent(event);
        } catch (Exception e) {
            LOG.warnf("Failed to process LiveKit webhook: %s", e.getMessage());
            return Response.status(400).entity(Map.of("error", "invalid_webhook")).build();
        }

        return Response.ok().build();
    }

    /** Validate LiveKit webhook JWT — verifies signature and body SHA-256 hash */
    private boolean validateWebhookJwt(String body, HttpHeaders headers) {
        String authHeader = headers.getHeaderString("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) return false;
        String token = authHeader.substring(7);

        try {
            String secret = liveKitService.getWebhookSecret();
            var key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            var claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();

            // Verify body hash
            String expectedHash = claims.get("sha256", String.class);
            if (expectedHash != null) {
                var digest = MessageDigest.getInstance("SHA-256");
                String actualHash = Base64.getEncoder().encodeToString(
                        digest.digest(body.getBytes(StandardCharsets.UTF_8)));
                if (!expectedHash.equals(actualHash)) {
                    LOG.warn("Webhook body hash mismatch");
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            LOG.warnf("Webhook JWT validation failed: %s", e.getMessage());
            return false;
        }
    }

    /** Process a webhook event (works for both managed and standard modes) */
    private void processEvent(LiveKitDto.WebhookEvent event) {
        if (event == null || event.event() == null) return;

        String roomName = event.room() != null ? event.room().name() : "";
        String channelId = extractChannelId(roomName);
        if (channelId == null) return;

        String userId = event.participant() != null ? event.participant().identity() : null;
        LOG.debugf("LiveKit webhook: %s room=%s channel=%s user=%s", event.event(), roomName, channelId, userId);

        switch (event.event()) {
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

    /** Extract channel ID from room name — handles both tenant-prefixed and plain */
    private static String extractChannelId(String roomName) {
        int idx = roomName.indexOf("-voice-");
        if (idx >= 0) return roomName.substring(idx + "-voice-".length());
        if (roomName.startsWith("voice-")) return roomName.substring("voice-".length());
        return null;
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
                if (participant.tracks() != null) {
                    for (var track : participant.tracks()) {
                        if ("AUDIO".equals(track.type())) {
                            liveKitService.muteTrack(roomName, userId, track.sid(), true);
                            break;
                        }
                    }
                }
            }
            if (deaf) {
                liveKitService.updateParticipant(roomName, userId,
                        new ParticipantPermission(false, true, null));
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
