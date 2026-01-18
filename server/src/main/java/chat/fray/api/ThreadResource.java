package chat.fray.api;

import chat.fray.auth.FileService;
import chat.fray.auth.FraySecurityContext;
import chat.fray.auth.RateLimitFilter;
import chat.fray.auth.RateLimitPolicy;
import chat.fray.auth.RateLimitService;
import chat.fray.db.ChannelRepository;
import chat.fray.db.DatabaseService;
import chat.fray.db.MessageRepository;
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
    @Inject ChannelRepository channelRepo;
    @Inject ReadStateRepository readStateRepo;
    @Inject FileService fileService;
    @Inject DatabaseService databaseService;
    @Inject RateLimitService rateLimitService;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

    // --- Channel-scoped thread endpoints ---

    @POST
    @Path("/channels/{channelId}/threads")
    public Response createThread(@PathParam("channelId") String channelId, CreateThreadRequest req) {
        var sc = sc();
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.CREATE_THREADS);
        var rl = checkRate("thread_create", sc.getUserId(), RateLimitPolicy.THREAD_CREATE);
        if (rl != null) return rl;

        if (channelRepo.findById(channelId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "Channel not found")).build();
        }

        if (req.content() == null || req.content().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_content", "message", "Content required")).build();
        }
        if (req.content().length() > 5000) {
            return Response.status(400).entity(Map.of("error", "content_too_long", "message", "Max 5000 characters")).build();
        }

        // Chat threads require parent_message_id; thread channel posts require title
        if (req.parent_message_id() == null && (req.title() == null || req.title().isBlank())) {
            return Response.status(400).entity(Map.of("error", "invalid_request", "message", "Either parent_message_id or title is required")).build();
        }

        // Validate parent message exists and belongs to channel
        if (req.parent_message_id() != null) {
            var parentMsg = messageRepo.findById(req.parent_message_id());
            if (parentMsg.isEmpty()) {
                return Response.status(404).entity(Map.of("error", "not_found", "message", "Parent message not found")).build();
            }
            if (!channelId.equals(parentMsg.get().get("channel_id"))) {
                return Response.status(400).entity(Map.of("error", "invalid_request", "message", "Parent message not in this channel")).build();
            }
            // Check if thread already exists on this message
            if (threadRepo.findByParentMessageId(req.parent_message_id()).isPresent()) {
                return Response.status(409).entity(Map.of("error", "thread_exists", "message", "Thread already exists on this message")).build();
            }
        }

        String threadId = ThreadRepository.newId();
        String messageId = MessageRepository.newId();

        // Create thread + first message atomically
        databaseService.transactionVoid(tx -> {
            threadRepo.create(tx, threadId, channelId, req.parent_message_id(), req.title(), sc.getUserId());
            messageRepo.create(tx, messageId, channelId, sc.getUserId(), req.content(), threadId);
        });

        // Link attachments
        if (req.attachment_ids() != null) {
            for (String fileId : req.attachment_ids()) {
                fileService.linkToMessage(fileId, messageId);
            }
        }

        var thread = threadRepo.findByIdWithMeta(threadId);
        var firstMessage = messageRepo.findByIdWithAuthor(messageId).map(this::withAttachments);

        if (thread.isPresent()) {
            var payload = new HashMap<>(thread.get());
            firstMessage.ifPresent(m -> payload.put("first_message", m));
            eventBus.publish(Event.of(EventType.THREAD_CREATE, payload, Scope.channel(channelId)));
            return Response.status(201).entity(payload).build();
        }
        return Response.status(500).build();
    }

    @GET
    @Path("/channels/{channelId}/threads")
    public Response listThreads(
            @PathParam("channelId") String channelId,
            @QueryParam("before") String before,
            @QueryParam("limit") @DefaultValue("25") int limit
    ) {
        permissionService.requirePermission(sc().getUserId(), channelId, Permission.VIEW_CHANNEL);
        if (channelRepo.findById(channelId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "Channel not found")).build();
        }
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;
        return Response.ok(threadRepo.findByChannelId(channelId, before, limit)).build();
    }

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
        var enriched = messages.stream().map(this::withAttachments).toList();
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

    public record CreateThreadRequest(String parent_message_id, String title, String content, List<String> attachment_ids) {}
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
        return result;
    }
}
