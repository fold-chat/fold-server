package chat.kith.api;

import chat.kith.auth.FileService;
import chat.kith.auth.KithSecurityContext;
import chat.kith.auth.RateLimitFilter;
import chat.kith.auth.RateLimitPolicy;
import chat.kith.auth.RateLimitService;
import chat.kith.db.EmojiRepository;
import chat.kith.event.*;
import chat.kith.security.Permission;
import chat.kith.security.PermissionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Path("/api/v0/emoji")
@Produces(MediaType.APPLICATION_JSON)
public class EmojiResource {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-zA-Z0-9_]{2,32}");
    private static final long MAX_EMOJI_SIZE = 256 * 1024; // 256KB
    private static final Set<String> ALLOWED_TYPES = Set.of("image/png", "image/gif", "image/webp");

    @Inject EmojiRepository emojiRepo;
    @Inject FileService fileService;
    @Inject RateLimitService rateLimitService;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Context ContainerRequestContext requestContext;

    @GET
    public Response list() {
        var emojis = emojiRepo.listAll().stream().map(this::enrichEmoji).toList();
        return Response.ok(emojis).build();
    }

    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(@RestForm("file") FileUpload file, @RestForm("name") String name) {
        var sc = sc();
        var rl = checkRate("emoji_upload", sc.getUserId(), RateLimitPolicy.EMOJI_UPLOAD);
        if (rl != null) return rl;

        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);

        // Validate name
        if (name == null || name.isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_name", "message", "Name is required")).build();
        }
        name = name.trim().toLowerCase();
        if (!NAME_PATTERN.matcher(name).matches()) {
            return Response.status(400).entity(Map.of("error", "invalid_name", "message", "Name must be 2-32 characters (letters, numbers, underscores)")).build();
        }
        if (emojiRepo.findByName(name).isPresent()) {
            return Response.status(409).entity(Map.of("error", "name_taken", "message", "An emoji with that name already exists")).build();
        }

        // Validate file
        if (file == null) {
            return Response.status(400).entity(Map.of("error", "missing_file", "message", "Emoji image file is required")).build();
        }
        if (!ALLOWED_TYPES.contains(file.contentType())) {
            return Response.status(400).entity(Map.of("error", "invalid_type", "message", "Only PNG, GIF, and WebP images are allowed")).build();
        }

        try {
            long fileSize = Files.size(file.uploadedFile());
            if (fileSize > MAX_EMOJI_SIZE) {
                return Response.status(400).entity(Map.of("error", "file_too_large", "message", "Emoji images must be under 256KB")).build();
            }

            // Upload via FileService
            var inputStream = Files.newInputStream(file.uploadedFile());
            var fileResult = fileService.upload(file.fileName(), file.contentType(), fileSize, inputStream, sc.getUserId());

            // Create emoji record
            String id = UUID.randomUUID().toString();
            emojiRepo.create(id, name, fileResult.get("id"), sc.getUserId());

            var emoji = emojiRepo.findById(id);
            if (emoji.isEmpty()) {
                return Response.status(500).entity(Map.of("error", "create_failed")).build();
            }

            // Re-query with file join for full data
            var fullEmoji = emojiRepo.findByName(name);
            // Build response manually since findByName doesn't join file
            var response = new HashMap<String, Object>();
            response.put("id", id);
            response.put("name", name);
            response.put("url", "/api/v0/files/" + fileResult.get("stored_name"));
            response.put("uploader_id", sc.getUserId());

            eventBus.publish(Event.of(EventType.EMOJI_CREATE, response, Scope.server()));

            return Response.status(201).entity(response).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid_file", "message", e.getMessage())).build();
        } catch (IOException e) {
            return Response.status(500).entity(Map.of("error", "upload_failed")).build();
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        var sc = sc();
        var rl = checkRate("emoji_delete", sc.getUserId(), RateLimitPolicy.EMOJI_DELETE);
        if (rl != null) return rl;

        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);

        var existing = emojiRepo.findById(id);
        if (existing.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }

        String emojiName = (String) existing.get().get("name");
        emojiRepo.delete(id);

        eventBus.publish(Event.of(EventType.EMOJI_DELETE,
                Map.of("id", id, "name", emojiName),
                Scope.server()));

        return Response.noContent().build();
    }

    private Map<String, Object> enrichEmoji(Map<String, Object> emoji) {
        var result = new HashMap<>(emoji);
        result.put("url", "/api/v0/files/" + emoji.get("stored_name"));
        return result;
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
}
