package chat.fold.api;

import chat.fold.auth.FoldSecurityContext;
import chat.fold.auth.RateLimitFilter;
import chat.fold.auth.RateLimitPolicy;
import chat.fold.auth.RateLimitService;
import chat.fold.db.ChannelRepository;
import chat.fold.db.DmRepository;
import chat.fold.db.SearchRepository;
import chat.fold.security.PermissionService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;
import java.util.stream.Collectors;

@Path("/api/v0/search")
@Produces(MediaType.APPLICATION_JSON)
public class SearchResource {

    @Inject SearchRepository searchRepo;
    @Inject ChannelRepository channelRepo;
    @Inject DmRepository dmRepo;
    @Inject RateLimitService rateLimitService;
    @Inject PermissionService permissionService;
    @Context ContainerRequestContext requestContext;

    @GET
    public Response search(
            @QueryParam("q") String query,
            @QueryParam("channel_id") String channelId,
            @QueryParam("author_id") String authorId,
            @QueryParam("before") String before,
            @QueryParam("after") String after,
            @QueryParam("has") String has,
            @QueryParam("limit") @DefaultValue("25") int limit
    ) {
        var sc = sc();

        // Validate query
        if (query == null || query.isBlank()) {
            return Response.status(400).entity(Map.of("error", "invalid_query", "message", "Query parameter 'q' is required")).build();
        }

        // Rate limit
        var rl = checkRate(sc.getUserId());
        if (rl != null) return rl;

        // Clamp limit
        if (limit < 1) limit = 1;
        if (limit > 100) limit = 100;

        // Resolve viewable channels — server channels only + user's own DM channels
        var serverChannelIds = channelRepo.listServerChannels().stream()
                .map(c -> (String) c.get("id"))
                .collect(Collectors.toSet());
        var dmChannelIds = dmRepo.findConversationsForUser(sc.getUserId()).stream()
                .map(c -> (String) c.get("channel_id"))
                .collect(Collectors.toSet());
        serverChannelIds.addAll(dmChannelIds);
        var viewable = permissionService.filterViewableChannels(sc.getUserId(), serverChannelIds);

        // If filtering by channel, verify it's viewable
        if (channelId != null && !viewable.contains(channelId)) {
            return Response.ok(java.util.List.of()).build();
        }

        boolean hasFile = "file".equals(has) || "image".equals(has);

        var results = searchRepo.search(query, viewable, channelId, authorId, before, after, hasFile, limit);
        return Response.ok(results).build();
    }

    private FoldSecurityContext sc() {
        return (FoldSecurityContext) requestContext.getSecurityContext();
    }

    private Response checkRate(String userId) {
        var policy = rateLimitService.resolvePolicy("search", RateLimitPolicy.SEARCH);
        var result = rateLimitService.check("user:" + userId + ":search", policy);
        requestContext.setProperty(RateLimitFilter.RATE_LIMIT_RESULT_KEY, result);
        if (!result.allowed()) {
            return Response.status(429)
                    .entity(Map.of("error", "rate_limited", "retry_after", result.retryAfter().toSeconds()))
                    .build();
        }
        return null;
    }
}
