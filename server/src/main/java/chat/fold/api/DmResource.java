package chat.fold.api;

import chat.fold.auth.FoldSecurityContext;
import chat.fold.auth.RateLimitFilter;
import chat.fold.auth.RateLimitPolicy;
import chat.fold.auth.RateLimitService;
import chat.fold.db.DmBlockRepository;
import chat.fold.db.DmRepository;
import chat.fold.db.UserRepository;
import chat.fold.event.*;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.*;

@Path("/api/v0/dm")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class DmResource {

    @Inject DmRepository dmRepo;
    @Inject DmBlockRepository dmBlockRepo;
    @Inject UserRepository userRepo;
    @Inject PermissionService permissionService;
    @Inject RateLimitService rateLimitService;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

    /** Open or return existing 1-on-1 DM */
    @POST
    public Response openDm(OpenDmRequest req) {
        var sc = sc();
        if (req.user_id() == null || req.user_id().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_user_id", "message", "user_id required")).build();
        }
        if (req.user_id().equals(sc.getUserId())) {
            return Response.status(400).entity(Map.of("error", "invalid_user_id", "message", "Cannot DM yourself")).build();
        }

        // Rate limit
        var rl = checkRate("message_send", sc.getUserId(), RateLimitPolicy.MESSAGE_SEND);
        if (rl != null) return rl;

        // Permission check
        permissionService.requireServerPermission(sc.getUserId(), Permission.INITIATE_DM);

        // Target exists?
        var target = userRepo.findById(req.user_id());
        if (target.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "User not found")).build();
        }

        // Block check
        if (dmBlockRepo.isBlockedEither(sc.getUserId(), req.user_id())) {
            return Response.status(403).entity(Map.of("error", "dm_blocked", "message", "Cannot open DM with this user")).build();
        }

        var result = dmRepo.findOrCreateOneOnOne(sc.getUserId(), req.user_id());
        String channelId = (String) result.get("channel_id");
        boolean created = (boolean) result.get("created");

        var participants = dmRepo.findParticipantDetails(channelId);
        var responseData = new LinkedHashMap<String, Object>();
        responseData.put("channel_id", channelId);
        responseData.put("participants", participants);

        if (created) {
            // Publish DM_CONVERSATION_CREATE to both participants
            eventBus.publish(Event.of(
                    EventType.DM_CONVERSATION_CREATE,
                    responseData,
                    Scope.users(Set.of(sc.getUserId(), req.user_id()))
            ));
            return Response.status(201).entity(responseData).build();
        }
        return Response.ok(responseData).build();
    }

    /** List user's DM conversations */
    @GET
    public Response listDms() {
        var sc = sc();
        var conversations = dmRepo.findConversationsForUser(sc.getUserId());

        // Enrich with participant details
        var result = new ArrayList<Map<String, Object>>();
        for (var conv : conversations) {
            var enriched = new LinkedHashMap<String, Object>(conv);
            enriched.put("participants", dmRepo.findParticipantDetails((String) conv.get("channel_id")));
            // Convert is_blocked from Long to boolean
            enriched.put("is_blocked", Objects.equals(conv.get("is_blocked"), 1L));
            result.add(enriched);
        }
        return Response.ok(result).build();
    }

    /** Block a user from DMing */
    @POST
    @Path("/block")
    public Response blockUser(BlockRequest req) {
        var sc = sc();
        if (req.user_id() == null || req.user_id().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_user_id", "message", "user_id required")).build();
        }
        if (req.user_id().equals(sc.getUserId())) {
            return Response.status(400).entity(Map.of("error", "invalid_user_id", "message", "Cannot block yourself")).build();
        }
        dmBlockRepo.block(sc.getUserId(), req.user_id());
        return Response.noContent().build();
    }

    /** Unblock a user */
    @DELETE
    @Path("/block/{userId}")
    public Response unblockUser(@PathParam("userId") String userId) {
        dmBlockRepo.unblock(sc().getUserId(), userId);
        return Response.noContent().build();
    }

    /** Get blocked user IDs */
    @GET
    @Path("/blocks")
    public Response getBlocks() {
        return Response.ok(dmBlockRepo.getBlockedIds(sc().getUserId())).build();
    }

    // --- DTOs ---
    public record OpenDmRequest(String user_id) {}
    public record BlockRequest(String user_id) {}

    // --- Helpers ---
    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
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
