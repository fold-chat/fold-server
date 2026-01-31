package chat.kith.api;

import chat.kith.auth.FileService;
import chat.kith.auth.KithSecurityContext;
import chat.kith.auth.RateLimitFilter;
import chat.kith.auth.RateLimitPolicy;
import chat.kith.auth.RateLimitService;
import chat.kith.db.DatabaseService;
import chat.kith.db.MessageRepository;
import chat.kith.db.ReactionRepository;
import chat.kith.db.ThreadRepository;
import chat.kith.event.*;
import chat.kith.security.Permission;
import chat.kith.security.PermissionService;
import chat.kith.util.MentionParser;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v0/messages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MessageResource {

    @Inject DatabaseService dbService;
    @Inject MessageRepository messageRepo;
    @Inject ReactionRepository reactionRepo;
    @Inject ThreadRepository threadRepo;
    @Inject FileService fileService;
    @Inject RateLimitService rateLimitService;
    @Inject PermissionService permissionService;
    @Inject MentionParser mentionParser;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") String id) {
        var msgOpt = messageRepo.findByIdWithAuthor(id);
        if (msgOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var msg = msgOpt.get();
        permissionService.requirePermission(sc().getUserId(), (String) msg.get("channel_id"), Permission.VIEW_CHANNEL);
        return Response.ok(withAttachmentsAndMentions(msg)).build();
    }

    @PATCH
    @Path("/{id}")
    public Response edit(@PathParam("id") String id, EditMessageRequest req) {
        var sc = sc();
        var rl = checkRate("message_edit", sc.getUserId(), RateLimitPolicy.MESSAGE_EDIT);
        if (rl != null) return rl;

        var msgOpt = messageRepo.findById(id);
        if (msgOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var msg = msgOpt.get();

        boolean isAuthor = sc.getUserId().equals(msg.get("author_id"));
        if (isAuthor) {
            permissionService.requirePermission(sc.getUserId(), (String) msg.get("channel_id"), Permission.MANAGE_OWN_MESSAGES);
        } else {
            permissionService.requirePermission(sc.getUserId(), (String) msg.get("channel_id"), Permission.MANAGE_MESSAGES);
        }
        if (req.content() == null || req.content().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_content", "message", "Content required")).build();
        }
        if (req.content().length() > 5000) {
            return Response.status(400).entity(Map.of("error", "content_too_long", "message", "Max 5000 characters")).build();
        }

        messageRepo.updateContent(id, req.content());
        var updated = messageRepo.findByIdWithAuthor(id).map(this::withAttachmentsAndMentions);
        updated.ifPresent(m -> eventBus.publish(Event.of(EventType.MESSAGE_UPDATE, m, Scope.channel((String) msg.get("channel_id")))));
        return updated
                .map(m -> Response.ok(m).build())
                .orElse(Response.status(500).build());
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        var sc = sc();
        var rl = checkRate("message_delete", sc.getUserId(), RateLimitPolicy.MESSAGE_DELETE);
        if (rl != null) return rl;

        var msgOpt = messageRepo.findById(id);
        if (msgOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }

        var msgToDelete = msgOpt.get();
        boolean isAuthor = sc.getUserId().equals(msgToDelete.get("author_id"));
        if (isAuthor) {
            permissionService.requirePermission(sc.getUserId(), (String) msgToDelete.get("channel_id"), Permission.MANAGE_OWN_MESSAGES);
        } else {
            permissionService.requirePermission(sc.getUserId(), (String) msgToDelete.get("channel_id"), Permission.MANAGE_MESSAGES);
        }

        String channelId = (String) msgToDelete.get("channel_id");

        // Check if message has an attached thread
        var attachedThread = threadRepo.findByParentMessageId(id);
        if (attachedThread.isPresent()) {
            // Deleting a message with a thread requires MANAGE_MESSAGES (even if author)
            // because the thread may contain other users' messages
            permissionService.requirePermission(sc.getUserId(), channelId, Permission.MANAGE_MESSAGES);

            String threadId = (String) attachedThread.get().get("id");
            // Delete thread first (cascades to thread messages), then parent message
            dbService.transactionVoid(tx -> {
                tx.execute("DELETE FROM thread WHERE id = ?", threadId);
                tx.execute("DELETE FROM message WHERE id = ?", id);
            });
            eventBus.publish(Event.of(EventType.THREAD_DELETE, Map.of("id", threadId, "channel_id", channelId), Scope.channel(channelId)));
        } else {
            messageRepo.delete(id);
        }

        eventBus.publish(Event.of(EventType.MESSAGE_DELETE, Map.of("id", id, "channel_id", channelId), Scope.channel(channelId)));
        return Response.noContent().build();
    }

    // --- Reactions ---

    @PUT
    @Path("/{messageId}/reactions/{emoji}")
    public Response addReaction(@PathParam("messageId") String messageId, @PathParam("emoji") String emoji) {
        var sc = sc();
        var rl = checkRate("reaction_add", sc.getUserId(), RateLimitPolicy.REACTION_ADD);
        if (rl != null) return rl;

        var msgOpt = messageRepo.findById(messageId);
        if (msgOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        var msg = msgOpt.get();
        String channelId = (String) msg.get("channel_id");
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.VIEW_CHANNEL);
        permissionService.requirePermission(sc.getUserId(), channelId, Permission.ADD_REACTIONS);

        if (reactionRepo.countUniqueEmojiForMessage(messageId) >= 25) {
            // Only block if this would be a NEW emoji (user might already have reacted with an existing one)
            var existing = reactionRepo.findByMessageId(messageId);
            boolean emojiExists = existing.stream().anyMatch(r -> emoji.equals(r.get("emoji")));
            if (!emojiExists) {
                return Response.status(400).entity(Map.of("error", "max_reactions", "message", "Max 25 unique emoji per message")).build();
            }
        }

        String id = UUID.randomUUID().toString();
        reactionRepo.create(id, messageId, sc.getUserId(), emoji);

        eventBus.publish(Event.of(EventType.REACTION_ADD,
                Map.of("message_id", messageId, "channel_id", channelId,
                        "user_id", sc.getUserId(), "emoji", emoji, "username", sc.getUsername()),
                Scope.channel(channelId)));
        return Response.noContent().build();
    }

    @DELETE
    @Path("/{messageId}/reactions/{emoji}")
    public Response removeReaction(@PathParam("messageId") String messageId, @PathParam("emoji") String emoji) {
        var sc = sc();
        var rl = checkRate("reaction_remove", sc.getUserId(), RateLimitPolicy.REACTION_REMOVE);
        if (rl != null) return rl;

        var msgOpt = messageRepo.findById(messageId);
        if (msgOpt.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        String channelId = (String) msgOpt.get().get("channel_id");

        reactionRepo.delete(messageId, sc.getUserId(), emoji);

        eventBus.publish(Event.of(EventType.REACTION_REMOVE,
                Map.of("message_id", messageId, "channel_id", channelId,
                        "user_id", sc.getUserId(), "emoji", emoji),
                Scope.channel(channelId)));
        return Response.noContent().build();
    }

    public record EditMessageRequest(String content) {}

    private KithSecurityContext sc() {
        return (KithSecurityContext) requestContext.getSecurityContext();
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
        result.put("reactions", groupReactions((String) msg.get("id"), reactionRepo.findByMessageId((String) msg.get("id")), null));
        return result;
    }

    private Map<String, Object> withAttachmentsAndMentions(Map<String, Object> msg) {
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

    /**
     * Enrich a list of messages with attachments and grouped reactions (batch).
     */
    List<Map<String, Object>> withAttachmentsAndReactions(List<Map<String, Object>> messages, String currentUserId) {
        var msgIds = messages.stream().map(m -> (String) m.get("id")).toList();
        var reactionsByMsg = reactionRepo.findByMessageIds(msgIds);
        return messages.stream().map(msg -> {
            var result = new HashMap<>(msg);
            // attachments
            var attachments = fileService.getAttachments((String) msg.get("id"));
            result.put("attachments", attachments.stream().map(a -> {
                var m = new HashMap<>(a);
                m.put("url", "/api/v0/files/" + a.get("stored_name"));
                return (Map<String, Object>) m;
            }).toList());
            // reactions
            var reactions = reactionsByMsg.getOrDefault((String) msg.get("id"), List.of());
            result.put("reactions", groupReactions((String) msg.get("id"), reactions, currentUserId));
            return (Map<String, Object>) result;
        }).toList();
    }

    /**
     * Group raw reaction rows into [{emoji, count, users: [userId...], me: bool}].
     */
    static List<Map<String, Object>> groupReactions(String messageId, List<Map<String, Object>> rows, String currentUserId) {
        var byEmoji = new LinkedHashMap<String, List<String>>();
        for (var row : rows) {
            var emoji = (String) row.get("emoji");
            byEmoji.computeIfAbsent(emoji, k -> new ArrayList<>()).add((String) row.get("user_id"));
        }
        var result = new ArrayList<Map<String, Object>>();
        for (var entry : byEmoji.entrySet()) {
            result.add(Map.of(
                    "emoji", entry.getKey(),
                    "count", entry.getValue().size(),
                    "users", entry.getValue(),
                    "me", currentUserId != null && entry.getValue().contains(currentUserId)
            ));
        }
        return result;
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
