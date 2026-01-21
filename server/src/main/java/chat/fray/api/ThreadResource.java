package chat.fray.api;

import chat.fray.auth.FileService;
import chat.fray.auth.FraySecurityContext;
import chat.fray.auth.RateLimitFilter;
import chat.fray.auth.RateLimitPolicy;
import chat.fray.auth.RateLimitService;
import chat.fray.db.MessageRepository;
import chat.fray.db.ReactionRepository;
import chat.fray.db.ReadStateRepository;
import chat.fray.db.ThreadRepository;
import chat.fray.event.*;
import chat.fray.security.Permission;
import chat.fray.security.PermissionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Path("/api/v0")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ThreadResource {

    @Inject ThreadRepository threadRepo;
    @Inject MessageRepository messageRepo;
    @Inject ReactionRepository reactionRepo;
    @Inject ReadStateRepository readStateRepo;
    @Inject FileService fileService;
    @Inject RateLimitService rateLimitService;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

    // --- Thread-scoped endpoints ---

    @GET
    @Path("/threads/{threadId}")
    public Response getThread(@PathParam("threadId") String threadId) {
        var thread = threadRepo.findByIdWithMeta(threadId);
        if (thread.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        permissionService.requirePermission(sc().getUserId(), (String) thread.get().get("channel_id"), Permission.VIEW_CHANNEL);
        return Response.ok(thread.get()).build();
    }

    @GET
    @Path("/threads/{threadId}/messages")
    public Response listThreadMessages(
            @PathParam("threadId") String threadId,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        var thread = threadRepo.findById(threadId);
        if (thread.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        permissionService.requirePermission(sc().getUserId(), (String) thread.get().get("channel_id"), Permission.VIEW_CHANNEL);
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;
        var messages = messageRepo.paginateThreadMessages(threadId, before, after, limit);
        var enriched = withAttachmentsAndReactions(messages, sc().getUserId());
        return Response.ok(enriched).build();
    }

    @POST
    @Path("/threads/{threadId}/messages")
    public Response replyToThread(@PathParam("threadId") String threadId, ReplyRequest req) {
        var sc = sc();
        var thread = threadRepo.findById(threadId);
        if (thread.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var t = thread.get();
        String channelId = (String) t.get("channel_id");
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.SEND_MESSAGES);

        // Check locked
        long locked = t.get("locked") instanceof Long l ? l : 0;
        if (locked != 0) {
            if (!permissionService.hasPermission(sc.getUserId(), channelId, Permission.SEND_IN_LOCKED_THREADS)) {
                return Response.status(403).entity(Map.of("error", "thread_locked", "message", "This thread is locked")).build();
            }
        }

        var rl = checkRate("message_send", sc.getUserId(), RateLimitPolicy.MESSAGE_SEND);
        if (rl != null) return rl;

        boolean hasAttachments = req.attachment_ids() != null && !req.attachment_ids().isEmpty();
        if ((req.content() == null || req.content().isBlank()) && !hasAttachments) {
            return Response.status(400).entity(Map.of("error", "invalid_content", "message", "Content required")).build();
        }
        if (req.content() != null && req.content().length() > 5000) {
            return Response.status(400).entity(Map.of("error", "content_too_long", "message", "Max 5000 characters")).build();
        }

        String id = MessageRepository.newId();
        messageRepo.create(id, channelId, sc.getUserId(), req.content(), threadId);
        threadRepo.updateLastActivity(threadId);

        if (req.attachment_ids() != null) {
            for (String fileId : req.attachment_ids()) {
                fileService.linkToMessage(fileId, id);
            }
        }

        var created = messageRepo.findByIdWithAuthor(id).map(this::withAttachments);
        created.ifPresent(m -> eventBus.publish(Event.of(EventType.MESSAGE_CREATE, m, Scope.channel(channelId))));
        return created
                .map(m -> Response.status(201).entity(m).build())
                .orElse(Response.status(500).build());
    }

    @PATCH
    @Path("/threads/{threadId}")
    public Response updateThread(@PathParam("threadId") String threadId, UpdateThreadRequest req) {
        var sc = sc();
        var thread = threadRepo.findById(threadId);
        if (thread.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var t = thread.get();
        String channelId = (String) t.get("channel_id");

        boolean isAuthor = sc.getUserId().equals(t.get("author_id"));
        if (isAuthor) {
            permissionService.requirePermission(sc.getUserId(), channelId, Permission.MANAGE_OWN_THREADS);
        } else {
            permissionService.requirePermission(sc.getUserId(), channelId, Permission.MANAGE_THREADS);
        }

        // Non-author can't change title unless they have MANAGE_THREADS (already checked above)
        // Only users with MANAGE_THREADS can lock/unlock
        if (req.locked() != null && isAuthor && !permissionService.hasPermission(sc.getUserId(), channelId, Permission.MANAGE_THREADS)) {
            return Response.status(403).entity(Map.of("error", "forbidden", "message", "Cannot lock/unlock threads")).build();
        }

        threadRepo.update(threadId, req.title(), req.locked());
        var updated = threadRepo.findByIdWithMeta(threadId);
        updated.ifPresent(u -> eventBus.publish(Event.of(EventType.THREAD_UPDATE, u, Scope.channel(channelId))));
        return updated
                .map(u -> Response.ok(u).build())
                .orElse(Response.status(500).build());
    }

    @DELETE
    @Path("/threads/{threadId}")
    public Response deleteThread(@PathParam("threadId") String threadId) {
        var sc = sc();
        var thread = threadRepo.findById(threadId);
        if (thread.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var t = thread.get();
        String channelId = (String) t.get("channel_id");

        boolean isAuthor = sc.getUserId().equals(t.get("author_id"));
        if (isAuthor) {
            permissionService.requirePermission(sc.getUserId(), channelId, Permission.MANAGE_OWN_THREADS);
        } else {
            permissionService.requirePermission(sc.getUserId(), channelId, Permission.MANAGE_THREADS);
        }

        threadRepo.delete(threadId);
        eventBus.publish(Event.of(EventType.THREAD_DELETE, Map.of("id", threadId, "channel_id", channelId), Scope.channel(channelId)));
        return Response.noContent().build();
    }

    @PUT
    @Path("/threads/{threadId}/read-state")
    public Response updateThreadReadState(@PathParam("threadId") String threadId, ThreadReadStateRequest req) {
        var sc = sc();
        var thread = threadRepo.findById(threadId);
        if (thread.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        readStateRepo.upsertThread(sc.getUserId(), threadId, req.last_read_message_id());
        return Response.noContent().build();
    }

    // --- DTOs ---

    public record ReplyRequest(String content, List<String> attachment_ids) {}
    public record UpdateThreadRequest(String title, Integer locked) {}
    public record ThreadReadStateRequest(String last_read_message_id) {}

    // --- Helpers ---

    private FraySecurityContext sc() {
        return (FraySecurityContext) requestContext.getSecurityContext();
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

    private Map<String, Object> withAttachments(Map<String, Object> msg) {
        var attachments = fileService.getAttachments((String) msg.get("id"));
        var enriched = attachments.stream().map(a -> {
            var m = new HashMap<>(a);
            m.put("url", "/api/v0/files/" + a.get("stored_name"));
            return (Map<String, Object>) m;
        }).toList();
        var result = new HashMap<>(msg);
        result.put("attachments", enriched);
        result.put("reactions", MessageResource.groupReactions((String) msg.get("id"), reactionRepo.findByMessageId((String) msg.get("id")), null));
        return result;
    }

    private List<Map<String, Object>> withAttachmentsAndReactions(List<Map<String, Object>> messages, String currentUserId) {
        var msgIds = messages.stream().map(m -> (String) m.get("id")).toList();
        var reactionsByMsg = reactionRepo.findByMessageIds(msgIds);
        return messages.stream().map(msg -> {
            var result = new HashMap<>(msg);
            var attachments = fileService.getAttachments((String) msg.get("id"));
            result.put("attachments", attachments.stream().map(a -> {
                var m = new HashMap<>(a);
                m.put("url", "/api/v0/files/" + a.get("stored_name"));
                return (Map<String, Object>) m;
            }).toList());
            var reactions = reactionsByMsg.getOrDefault((String) msg.get("id"), List.of());
            result.put("reactions", MessageResource.groupReactions((String) msg.get("id"), reactions, currentUserId));
            return (Map<String, Object>) result;
        }).toList();
    }
}
