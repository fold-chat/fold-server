package chat.kith.livekit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * DTOs for LiveKit Twirp RoomService JSON API.
 * Field names use camelCase to match Twirp JSON serialization.
 */
public final class LiveKitDto {
    private LiveKitDto() {}

    // --- Requests ---

    public record ListRoomsRequest(List<String> names) {
        public ListRoomsRequest() { this(null); }
    }

    public record RoomParticipantsRequest(String room) {}

    public record GetParticipantRequest(String room, String identity) {}

    public record MuteTrackRequest(String room, String identity, String trackSid, boolean muted) {}

    public record RemoveParticipantRequest(String room, String identity) {}

    public record UpdateParticipantRequest(String room, String identity, String name,
                                           String metadata, ParticipantPermission permission) {}

    public record DeleteRoomRequest(String room) {}

    // --- Responses ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListRoomsResponse(List<Room> rooms) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ListParticipantsResponse(List<ParticipantInfo> participants) {}

    // --- Model types ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Room(String name, String sid, int numParticipants, int numPublishers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParticipantInfo(String identity, String name, String sid,
                                  String state, List<TrackInfo> tracks) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TrackInfo(String sid, String type, boolean muted) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ParticipantPermission(Boolean canSubscribe, Boolean canPublish,
                                        Boolean canPublishData) {}

    // --- Webhook event (JSON body from LiveKit) ---

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookEvent(String event, Room room, ParticipantInfo participant) {}
}
