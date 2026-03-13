package chat.fold.livekit;

import chat.fold.livekit.LiveKitDto.*;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Quarkus REST client for LiveKit's Twirp RoomService API.
 * All endpoints are POST with JSON bodies.
 */
@Path("/twirp/livekit.RoomService")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public interface LiveKitRoomService {

    @POST @Path("/ListRooms")
    ListRoomsResponse listRooms(ListRoomsRequest request);

    @POST @Path("/ListParticipants")
    ListParticipantsResponse listParticipants(RoomParticipantsRequest request);

    @POST @Path("/GetParticipant")
    ParticipantInfo getParticipant(GetParticipantRequest request);

    @POST @Path("/MutePublishedTrack")
    TrackInfo mutePublishedTrack(MuteTrackRequest request);

    @POST @Path("/RemoveParticipant")
    void removeParticipant(RemoveParticipantRequest request);

    @POST @Path("/UpdateParticipant")
    ParticipantInfo updateParticipant(UpdateParticipantRequest request);

    @POST @Path("/DeleteRoom")
    void deleteRoom(DeleteRoomRequest request);
}
