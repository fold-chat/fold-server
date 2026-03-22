package chat.fold.api;

import chat.fold.auth.FileService;
import chat.fold.auth.FoldSecurityContext;
import chat.fold.db.BotRepository;
import chat.fold.db.UserRepository;
import chat.fold.event.Event;
import chat.fold.event.EventBus;
import chat.fold.event.EventType;
import chat.fold.event.Scope;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Path("/api/v0/bots")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BotResource {

    @Inject BotRepository botRepo;
    @Inject UserRepository userRepo;
    @Inject PermissionService permissionService;
    @Inject FileService fileService;
    @Inject EventBus eventBus;
    @Inject chat.fold.service.HelloCacheService helloCacheService;
    @Context ContainerRequestContext requestContext;

    // --- Create bot ---

    @POST
    public Response create(CreateBotRequest req) {
        var sc = sc();
        permissionService.requireServerPermission(sc.getUserId(), Permission.MANAGE_SERVER);

        if (req == null || req.username() == null || req.username().isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_username", "message", "Username required")).build();
        }

        String id = UUID.randomUUID().toString();
        try {
            botRepo.createBot(id, req.username(), req.display_name());
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("UNIQUE")) {
                return Response.status(409).entity(Map.of("error", "username_taken", "message", "Username already taken")).build();
            }
            throw e;
        }

        // Generate initial token
        String rawToken = BotRepository.generateToken();
        String tokenHash = BotRepository.hashToken(rawToken);
        String tokenId = UUID.randomUUID().toString();
        botRepo.createToken(tokenId, id, tokenHash);

        var bot = botRepo.findBotById(id).orElseThrow();

        // Notify connected clients of the new bot
        helloCacheService.invalidateMembers();
        userRepo.findMemberById(id).ifPresent(member ->
                eventBus.publish(Event.of(EventType.MEMBER_JOIN, member, Scope.server()))
        );

        var result = new LinkedHashMap<>(bot);
        result.put("token", rawToken);
        return Response.status(201).entity(result).build();
    }

    // --- List bots ---

    @GET
    public Response list() {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_SERVER);
        return Response.ok(botRepo.listBots()).build();
    }

    // --- Update display name ---

    @PATCH
    @Path("/{id}")
    public Response update(@PathParam("id") String id, UpdateBotRequest req) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_SERVER);

        var bot = botRepo.findBotById(id);
        if (bot.isEmpty()) return Response.status(404).entity(Map.of("error", "not_found")).build();

        if (req != null && req.display_name() != null) {
            botRepo.updateDisplayName(id, req.display_name());
        }

        return Response.ok(botRepo.findBotById(id).orElseThrow()).build();
    }

    // --- Upload avatar ---

    @POST
    @Path("/{id}/avatar")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response uploadAvatar(@PathParam("id") String id, @RestForm("file") FileUpload file) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_SERVER);

        var bot = botRepo.findBotById(id);
        if (bot.isEmpty()) return Response.status(404).entity(Map.of("error", "not_found")).build();

        if (file == null) return Response.status(400).entity(Map.of("error", "missing_file")).build();

        try {
            var inputStream = Files.newInputStream(file.uploadedFile());
            var result = fileService.upload(
                    file.fileName(),
                    file.contentType(),
                    Files.size(file.uploadedFile()),
                    inputStream,
                    id
            );
            botRepo.updateAvatarUrl(id, result.get("url"));
            return Response.ok(botRepo.findBotById(id).orElseThrow()).build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid_file", "message", e.getMessage())).build();
        } catch (IOException e) {
            return Response.status(500).entity(Map.of("error", "upload_failed")).build();
        }
    }

    // --- Regenerate token ---

    @POST
    @Path("/{id}/regenerate-token")
    public Response regenerateToken(@PathParam("id") String id, RegenerateTokenRequest req) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_SERVER);

        var bot = botRepo.findBotById(id);
        if (bot.isEmpty()) return Response.status(404).entity(Map.of("error", "not_found")).build();

        String rawToken = BotRepository.generateToken();
        String newHash = BotRepository.hashToken(rawToken);
        String newTokenId = UUID.randomUUID().toString();

        botRepo.regenerateToken(id, newTokenId, newHash);

        var result = new LinkedHashMap<String, Object>();
        result.put("bot_id", id);
        result.put("token", rawToken);
        result.put("old_token_revoked", true);
        result.put("message", "Previous token has been permanently revoked");
        return Response.ok(result).build();
    }

    // --- Enable / Disable ---

    @POST
    @Path("/{id}/disable")
    public Response disable(@PathParam("id") String id) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_SERVER);

        var bot = botRepo.findBotById(id);
        if (bot.isEmpty()) return Response.status(404).entity(Map.of("error", "not_found")).build();

        botRepo.setEnabled(id, false);
        eventBus.publish(Event.of(EventType.BOT_UPDATED, Map.of("user_id", id, "bot_enabled", 0), Scope.server()));
        return Response.ok(botRepo.findBotById(id).orElseThrow()).build();
    }

    @POST
    @Path("/{id}/enable")
    public Response enable(@PathParam("id") String id) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_SERVER);

        var bot = botRepo.findBotById(id);
        if (bot.isEmpty()) return Response.status(404).entity(Map.of("error", "not_found")).build();

        botRepo.setEnabled(id, true);
        eventBus.publish(Event.of(EventType.BOT_UPDATED, Map.of("user_id", id, "bot_enabled", 1), Scope.server()));
        return Response.ok(botRepo.findBotById(id).orElseThrow()).build();
    }

    // --- Delete ---

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") String id) {
        permissionService.requireServerPermission(sc().getUserId(), Permission.MANAGE_SERVER);

        var bot = botRepo.findBotById(id);
        if (bot.isEmpty()) return Response.status(404).entity(Map.of("error", "not_found")).build();

        botRepo.hardDeleteBot(id);
        helloCacheService.invalidateMembers();
        eventBus.publish(Event.of(EventType.MEMBER_LEAVE, Map.of("user_id", id), Scope.server()));
        return Response.noContent().build();
    }

    // --- DTOs ---

    public record CreateBotRequest(String username, String display_name) {}
    public record UpdateBotRequest(String display_name) {}
    public record RegenerateTokenRequest() {}

    // --- Helpers ---

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }
}
