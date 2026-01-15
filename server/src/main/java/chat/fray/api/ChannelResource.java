package chat.fray.api;

import chat.fray.auth.FileService;
import chat.fray.auth.FraySecurityContext;
import chat.fray.auth.RateLimitFilter;
import chat.fray.auth.RateLimitPolicy;
import chat.fray.auth.RateLimitService;
import chat.fray.db.ChannelRepository;
import chat.fray.db.MessageRepository;
import chat.fray.db.ReadStateRepository;
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
import java.util.Set;
import java.util.UUID;

@Path("/api/v0/channels")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ChannelResource {

    private static final Set<String> VALID_TYPES = Set.of("TEXT", "THREAD_CHANNEL", "VOICE");

    @Inject ChannelRepository channelRepo;
    @Inject MessageRepository messageRepo;
    @Inject ReadStateRepository readStateRepo;
    @Inject FileService fileService;
    @Inject RateLimitService rateLimitService;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

    @GET
    public Response list() {
        return Response.ok(channelRepo.listAll()).build();
    }

    @POST
    public Response create(CreateChannelRequest req) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        if (req.name() == null || req.name().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_name", "message", "Name required")).build();
        }
        String type = req.type() != null ? req.type() : "TEXT";
        if (!VALID_TYPES.contains(type)) {
            return Response.status(400).entity(Map.of("error", "invalid_type", "message", "Type must be TEXT, THREAD_CHANNEL, or VOICE")).build();
        }
        String id = UUID.randomUUID().toString();
        int position = req.position() != null ? req.position() : channelRepo.nextPosition();
        channelRepo.create(id, req.name().trim(), type, req.category_id(), req.topic(), req.description(), position);
        var created = channelRepo.findById(id);
        created.ifPresent(c -> eventBus.publish(Event.of(EventType.CHANNEL_CREATE, c, Scope.server())));
        return created
                .map(c -> Response.status(201).entity(c).build())
                .orElse(Response.status(500).build());
    }

    @PATCH
    @Path("/{id}")
    public Response update(@PathParam("id") String id, UpdateChannelRequest req) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        var existing = channelRepo.findById(id);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var ch = existing.get();
        String name = req.name() != null ? req.name().trim() : (String) ch.get("name");
        String topic = req.topic() != null ? req.topic() : (String) ch.get("topic");
        String description = req.description() != null ? req.description() : (String) ch.get("description");
        // Allow explicitly setting category_id to null (uncategorized)
        String categoryId = req.category_id_set() ? req.category_id() : (String) ch.get("category_id");
        int position = req.position() != null ? req.position() : ((Long) ch.get("position")).intValue();
        channelRepo.update(id, name, topic, description, categoryId, position);
        var updated = channelRepo.findById(id);
        updated.ifPresent(c -> eventBus.publish(Event.of(EventType.CHANNEL_UPDATE, c, Scope.server())));
        return updated
                .map(c -> Response.ok(c).build())
                .orElse(Response.status(500).build());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        if (channelRepo.findById(id).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        channelRepo.delete(id);
        eventBus.publish(Event.of(EventType.CHANNEL_DELETE, Map.of("id", id), Scope.server()));
        return Response.noContent().build();
    }

    // --- Messages ---

    @GET
    @Path("/{channelId}/messages")
    public Response listMessages(
            @PathParam("channelId") String channelId,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        permissionService.requirePermission(sc().getUserId(), channelId, Permission.VIEW_CHANNEL);
        if (channelRepo.findById(channelId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "Channel not found")).build();
        }
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;

        var messages = messageRepo.paginateWithAuthor(channelId, before, after, limit);
        var enriched = messages.stream().map(this::withAttachments).toList();
        return Response.ok(enriched).build();
    }

    @POST
    @Path("/{channelId}/messages")
    public Response sendMessage(@PathParam("channelId") String channelId, SendMessageRequest req) {
        var sc = sc();
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.SEND_MESSAGES);
        var rl = checkRate("message_send", sc.getUserId(), RateLimitPolicy.MESSAGE_SEND);
        if (rl != null) return rl;

        if (channelRepo.findById(channelId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "Channel not found")).build();
        }
        boolean hasAttachments = req.attachment_ids() != null && !req.attachment_ids().isEmpty();
        if ((req.content() == null || req.content().isBlank()) && !hasAttachments) {
            return Response.status(400).entity(Map.of("error", "invalid_content", "message", "Content required")).build();
        }
        if (req.content().length() > 5000) {
            return Response.status(400).entity(Map.of("error", "content_too_long", "message", "Max 5000 characters")).build();
        }

        String id = MessageRepository.newId();
        messageRepo.create(id, channelId, sc.getUserId(), req.content());

        if (req.attachment_ids() != null) {
            for (String fileId : req.attachment_ids()) {
                fileService.linkToMessage(fileId, id);
            }
        }

        var created = messageRepo.findById(id).map(this::withAttachments);
        created.ifPresent(m -> eventBus.publish(Event.of(EventType.MESSAGE_CREATE, m, Scope.channel(channelId))));
        return created
                .map(m -> Response.status(201).entity(m).build())
                .orElse(Response.status(500).build());
    }

    @PUT
    @Path("/{channelId}/read-state")
    public Response updateReadState(@PathParam("channelId") String channelId, ReadStateRequest req) {
        var sc = sc();
        if (channelRepo.findById(channelId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "Channel not found")).build();
        }
        readStateRepo.upsert(sc.getUserId(), channelId, req.last_read_message_id());
        return Response.noContent().build();
    }

    // --- DTOs ---

    public record CreateChannelRequest(String name, String type, String category_id, String topic, String description, Integer position) {}
    public record UpdateChannelRequest(String name, String topic, String description, String category_id, Integer position) {
        public boolean category_id_set() {
            return true;
        }
    }
    public record SendMessageRequest(String content, List<String> attachment_ids) {}
    public record ReadStateRequest(String last_read_message_id) {}

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
