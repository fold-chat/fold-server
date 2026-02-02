package chat.kith.api;

import chat.kith.auth.FileService;
import chat.kith.auth.KithSecurityContext;
import chat.kith.auth.RateLimitFilter;
import chat.kith.auth.RateLimitPolicy;
import chat.kith.auth.RateLimitService;
import chat.kith.db.CategoryRepository;
import chat.kith.db.ChannelRepository;
import chat.kith.db.DatabaseService;
import chat.kith.db.MessageRepository;
import chat.kith.db.ReactionRepository;
import chat.kith.db.ReadStateRepository;
import chat.kith.db.RoleRepository;
import chat.kith.db.ThreadRepository;
import chat.kith.db.UserRepository;
import chat.kith.event.*;
import chat.kith.security.Permission;
import chat.kith.security.PermissionService;
import chat.kith.db.VoiceKeyRepository;
import chat.kith.db.VoiceStateRepository;
import chat.kith.config.KithLiveKitConfig;
import chat.kith.service.AuditLogService;
import chat.kith.service.LiveKitService;
import chat.kith.service.RoleService;
import chat.kith.util.MentionParser;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
    @Inject CategoryRepository categoryRepo;
    @Inject MessageRepository messageRepo;
    @Inject ReactionRepository reactionRepo;
    @Inject ReadStateRepository readStateRepo;
    @Inject ThreadRepository threadRepo;
    @Inject DatabaseService databaseService;
    @Inject FileService fileService;
    @Inject RateLimitService rateLimitService;
    @Inject PermissionService permissionService;
    @Inject RoleService roleService;
    @Inject RoleRepository roleRepo;
    @Inject UserRepository userRepo;
    @Inject MentionParser mentionParser;
    @Inject EventBus eventBus;
    @Inject AuditLogService auditLogService;
    @Inject VoiceKeyRepository voiceKeyRepo;
    @Inject VoiceStateRepository voiceStateRepo;
    @Inject KithLiveKitConfig liveKitConfig;
    @Inject LiveKitService liveKitService;
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
        channelRepo.create(id, req.name().trim(), type, req.category_id(), req.topic(), req.description(), position, req.icon(), req.icon_url());
        // Auto-generate E2EE key for voice channels (only when E2EE enabled)
        if ("VOICE".equals(type) && liveKitConfig.e2ee()) {
            voiceKeyRepo.createKey(id);
        }
        var created = channelRepo.findById(id);
        created.ifPresent(c -> {
            eventBus.publish(Event.of(EventType.CHANNEL_CREATE, withCategory(c), Scope.server()));
            auditLogService.log(sc().getUserId(), "CHANNEL_CREATE", "channel", id, Map.of("name", req.name()));
        });
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
        String icon = req.icon_set() ? req.icon() : (String) ch.get("icon");
        String iconUrl = req.icon_url_set() ? req.icon_url() : (String) ch.get("icon_url");
        channelRepo.update(id, name, topic, description, categoryId, position, icon, iconUrl);
        var updated = channelRepo.findById(id);
        updated.ifPresent(c -> {
            eventBus.publish(Event.of(EventType.CHANNEL_UPDATE, withCategory(c), Scope.server()));
            auditLogService.log(sc().getUserId(), "CHANNEL_UPDATE", "channel", id);
        });
        return updated
                .map(c -> Response.ok(c).build())
                .orElse(Response.status(500).build());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        var existing = channelRepo.findById(id);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        // Voice channel cleanup
        if ("VOICE".equals(existing.get().get("type"))) {
            voiceStateRepo.deleteByChannel(id);
            voiceKeyRepo.deleteByChannel(id);
            if (liveKitService.isEnabled()) {
                liveKitService.deleteRoom("voice-" + id);
            }
        }
        channelRepo.delete(id);
        eventBus.publish(Event.of(EventType.CHANNEL_DELETE, Map.of("id", id), Scope.server()));
        auditLogService.log(sc().getUserId(), "CHANNEL_DELETE", "channel", id);
        return Response.noContent().build();
    }

    // --- Messages ---

    @GET
    @Path("/{channelId}/messages")
    public Response listMessages(
            @PathParam("channelId") String channelId,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("around") String around,
            @QueryParam("limit") @DefaultValue("50") int limit
    ) {
        permissionService.requirePermission(sc().getUserId(), channelId, Permission.VIEW_CHANNEL);
        if (channelRepo.findById(channelId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "Channel not found")).build();
        }
        if (limit < 1) limit = 1;
        if (limit > 200) limit = 200;

        if (around != null) {
            var messages = messageRepo.paginateAround(channelId, around, limit);
            var enriched = withAttachmentsAndReactions(messages, sc().getUserId());
            return Response.ok(enriched).build();
        }

        var messages = messageRepo.paginateWithAuthor(channelId, before, after, limit);
        var enriched = withAttachmentsAndReactions(messages, sc().getUserId());
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

        var created = messageRepo.findByIdWithAuthor(id).map(m -> withAttachmentsAndMentions(m, sc.getUserId()));
        created.ifPresent(m -> {
            eventBus.publish(Event.of(EventType.MESSAGE_CREATE, m, Scope.channel(channelId)));
            // Increment mention_count for mentioned users
            incrementMentionCounts(sc.getUserId(), channelId, req.content());
        });
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
        // upsert resets mention_count to 0
        readStateRepo.upsert(sc.getUserId(), channelId, req.last_read_message_id());
        eventBus.publish(Event.of(EventType.READ_STATE_UPDATE,
                Map.of("channel_id", channelId, "last_read_message_id", req.last_read_message_id(), "mention_count", 0),
                Scope.user(sc.getUserId())));
        return Response.noContent().build();
    }

    // --- Threads (channel-scoped) ---

    @POST
    @Path("/{channelId}/threads")
    public Response createThread(@PathParam("channelId") String channelId, CreateThreadRequest threadReq) {
        var sc = sc();
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.CREATE_THREADS);
        var rl = checkRate("thread_create", sc.getUserId(), RateLimitPolicy.THREAD_CREATE);
        if (rl != null) return rl;

        if (channelRepo.findById(channelId).isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found", "message", "Channel not found")).build();
        }

        if (threadReq.content() == null || threadReq.content().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_content", "message", "Content required")).build();
        }
        if (threadReq.content().length() > 5000) {
            return Response.status(400).entity(Map.of("error", "content_too_long", "message", "Max 5000 characters")).build();
        }

        if (threadReq.parent_message_id() == null && (threadReq.title() == null || threadReq.title().isBlank())) {
            return Response.status(400).entity(Map.of("error", "invalid_request", "message", "Either parent_message_id or title is required")).build();
        }

        if (threadReq.parent_message_id() != null) {
            var parentMsg = messageRepo.findById(threadReq.parent_message_id());
            if (parentMsg.isEmpty()) {
                return Response.status(404).entity(Map.of("error", "not_found", "message", "Parent message not found")).build();
            }
            if (!channelId.equals(parentMsg.get().get("channel_id"))) {
                return Response.status(400).entity(Map.of("error", "invalid_request", "message", "Parent message not in this channel")).build();
            }
            if (threadRepo.findByParentMessageId(threadReq.parent_message_id()).isPresent()) {
                return Response.status(409).entity(Map.of("error", "thread_exists", "message", "Thread already exists on this message")).build();
            }
        }

        String threadId = ThreadRepository.newId();
        String messageId = MessageRepository.newId();

        databaseService.transactionVoid(tx -> {
            threadRepo.create(tx, threadId, channelId, threadReq.parent_message_id(), threadReq.title(), sc.getUserId());
            messageRepo.create(tx, messageId, channelId, sc.getUserId(), threadReq.content(), threadId);
        });

        if (threadReq.attachment_ids() != null) {
            for (String fileId : threadReq.attachment_ids()) {
                fileService.linkToMessage(fileId, messageId);
            }
        }

        var thread = threadRepo.findByIdWithMeta(threadId);
        var firstMessage = messageRepo.findByIdWithAuthor(messageId).map(m -> withAttachmentsAndMentions(m, sc.getUserId()));

        if (thread.isPresent()) {
            var payload = new HashMap<>(thread.get());
            firstMessage.ifPresent(m -> payload.put("first_message", m));
            eventBus.publish(Event.of(EventType.THREAD_CREATE, payload, Scope.channel(channelId)));
            return Response.status(201).entity(payload).build();
        }
        return Response.status(500).build();
    }

    @GET
    @Path("/{channelId}/threads")
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

    @PATCH
    @Path("/reorder")
    public Response reorder(List<ReorderItem> items) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_CHANNELS);
        if (items == null || items.isEmpty()) {
            return Response.status(400).entity(Map.of("error", "invalid_body", "message", "Items required")).build();
        }
        for (var item : items) {
            if (item.id() == null || channelRepo.findById(item.id()).isEmpty()) {
                return Response.status(400).entity(Map.of("error", "invalid_id", "message", "Channel not found: " + item.id())).build();
            }
        }
        channelRepo.batchUpdatePositions(
                items.stream().map(i -> new chat.kith.db.ChannelRepository.IdPositionCategory(i.id(), i.position(), i.category_id())).toList()
        );
        for (var item : items) {
            channelRepo.findById(item.id()).ifPresent(c ->
                    eventBus.publish(Event.of(EventType.CHANNEL_UPDATE, withCategory(c), Scope.server())));
        }
        return Response.ok(channelRepo.listAll()).build();
    }

    // --- Channel permission overrides ---

    @GET
    @Path("/{channelId}/permissions")
    public Response listChannelOverrides(@PathParam("channelId") String channelId) {
        var overrides = roleRepo.findChannelOverrides(channelId).stream()
                .map(roleService::serializeOverride)
                .toList();
        return Response.ok(overrides).build();
    }

    @PUT
    @Path("/{channelId}/permissions/{roleId}")
    public Response upsertChannelOverride(
            @PathParam("channelId") String channelId,
            @PathParam("roleId") String roleId,
            OverrideRequest req
    ) {
        long allow = req.allow() != null ? Permission.fromNames(req.allow()) : 0L;
        long deny = req.deny() != null ? Permission.fromNames(req.deny()) : 0L;
        var override = roleService.upsertOverride(sc().getUserId(), channelId, roleId, allow, deny);
        return Response.ok(override).build();
    }

    @DELETE
    @Path("/{channelId}/permissions/{roleId}")
    public Response deleteChannelOverride(
            @PathParam("channelId") String channelId,
            @PathParam("roleId") String roleId
    ) {
        roleService.deleteOverride(sc().getUserId(), channelId, roleId);
        return Response.noContent().build();
    }

    // --- DTOs ---

    public record CreateChannelRequest(String name, String type, String category_id, String topic, String description, Integer position, String icon, String icon_url) {}
    public record OverrideRequest(List<String> allow, List<String> deny) {}
    public static class UpdateChannelRequest {
        public String name;
        public String topic;
        public String description;
        public String category_id;
        public Integer position;
        public String icon;
        public String icon_url;
        private boolean category_id_set = false;
        private boolean icon_set = false;
        private boolean icon_url_set = false;

        public String name() { return name; }
        public String topic() { return topic; }
        public String description() { return description; }
        public String category_id() { return category_id; }
        public Integer position() { return position; }
        public String icon() { return icon; }
        public String icon_url() { return icon_url; }
        public boolean category_id_set() { return category_id_set; }
        public boolean icon_set() { return icon_set; }
        public boolean icon_url_set() { return icon_url_set; }

        public void setCategory_id(String v) {
            this.category_id = v;
            this.category_id_set = true;
        }
        public void setIcon(String v) {
            this.icon = v;
            this.icon_set = true;
        }
        public void setIcon_url(String v) {
            this.icon_url = v;
            this.icon_url_set = true;
        }
    }
    public record SendMessageRequest(String content, List<String> attachment_ids) {}
    public record ReadStateRequest(String last_read_message_id) {}
    public record ReorderItem(String id, int position, String category_id) {}
    public record CreateThreadRequest(String parent_message_id, String title, String content, List<String> attachment_ids) {}

    // --- Helpers ---

    /** Resolve all mentioned user IDs from content and increment their mention_count. */
    private void incrementMentionCounts(String authorId, String channelId, String content) {
        if (content == null || content.isBlank()) return;
        var parsed = mentionParser.parse(content, authorId, channelId);
        var mentionedUserIds = new HashSet<String>();

        // Direct user mentions
        for (var u : parsed.users()) mentionedUserIds.add(u.id());

        // Role mentions → resolve to user IDs
        for (var r : parsed.roles()) mentionedUserIds.addAll(roleRepo.findUserIdsWithRole(r.id()));

        // @everyone → all active users
        if (parsed.mentionEveryone()) {
            userRepo.listAll().stream()
                    .map(u -> (String) u.get("id"))
                    .forEach(mentionedUserIds::add);
        }

        // Exclude author
        mentionedUserIds.remove(authorId);

        for (var userId : mentionedUserIds) {
            readStateRepo.incrementMentionCount(userId, channelId);
        }
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

    /** Embed the category object in a channel map (for events) */
    private Map<String, Object> withCategory(Map<String, Object> channel) {
        var catId = (String) channel.get("category_id");
        if (catId == null) return channel;
        var cat = categoryRepo.findById(catId);
        if (cat.isEmpty()) return channel;
        var result = new HashMap<>(channel);
        result.put("category", cat.get());
        return result;
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

    private Map<String, Object> withAttachmentsAndMentions(Map<String, Object> msg, String currentUserId) {
        var result = withAttachments(msg);
        var parsed = mentionParser.parse((String) msg.get("content"), (String) msg.get("author_id"), (String) msg.get("channel_id"));
        result.put("mentions", parsed.users().stream().map(u -> Map.of(
                "id", u.id(),
                "username", u.username(),
                "display_name", u.displayName()
        )).toList());
        result.put("mention_roles", parsed.roles().stream().map(r -> {
            var rm = new HashMap<String, Object>();
            rm.put("id", r.id());
            rm.put("name", r.name());
            rm.put("color", r.color());
            return (Map<String, Object>) rm;
        }).toList());
        result.put("mention_everyone", parsed.mentionEveryone());
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
            
            // Add mentions
            var parsed = mentionParser.parse((String) msg.get("content"), (String) msg.get("author_id"), (String) msg.get("channel_id"));
            result.put("mentions", parsed.users().stream().map(u -> Map.of(
                    "id", u.id(),
                    "username", u.username(),
                    "display_name", u.displayName()
            )).toList());
            result.put("mention_roles", parsed.roles().stream().map(r -> {
                var rm = new HashMap<String, Object>();
                rm.put("id", r.id());
                rm.put("name", r.name());
                rm.put("color", r.color());
                return (Map<String, Object>) rm;
            }).toList());
            result.put("mention_everyone", parsed.mentionEveryone());
            
            return (Map<String, Object>) result;
        }).toList();
    }
}
