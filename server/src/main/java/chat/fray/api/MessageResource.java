package chat.fray.api;

import chat.fray.auth.FileService;
import chat.fray.auth.FraySecurityContext;
import chat.fray.auth.RateLimitFilter;
import chat.fray.auth.RateLimitPolicy;
import chat.fray.auth.RateLimitService;
import chat.fray.db.MessageRepository;
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
import java.util.Map;

@Path("/api/v0/messages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MessageResource {

    @Inject MessageRepository messageRepo;
    @Inject FileService fileService;
    @Inject RateLimitService rateLimitService;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

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
        var updated = messageRepo.findByIdWithAuthor(id).map(this::withAttachments);
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
        messageRepo.delete(id);
        eventBus.publish(Event.of(EventType.MESSAGE_DELETE, Map.of("id", id, "channel_id", channelId), Scope.channel(channelId)));
        return Response.noContent().build();
    }

    public record EditMessageRequest(String content) {}

    private FraySecurityContext sc() {
        return (FraySecurityContext) requestContext.getSecurityContext();
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
