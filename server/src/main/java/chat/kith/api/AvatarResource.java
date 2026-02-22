package chat.kith.api;

import chat.kith.service.AvatarService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Response;

@Path("/api/v0/avatars")
public class AvatarResource {

    @Inject AvatarService avatarService;

    @GET
    @Path("/default/{username}")
    @Produces("image/svg+xml")
    public Response defaultAvatar(@PathParam("username") String username) {
        String svg = avatarService.generateSvg(username);
        return Response.ok(svg)
                .header("Cache-Control", "public, max-age=86400, immutable")
                .header("Content-Type", "image/svg+xml")
                .build();
    }
}
