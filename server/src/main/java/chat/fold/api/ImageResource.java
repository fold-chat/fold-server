package chat.fold.api;

import chat.fold.service.ExternalImageService;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

@Path("/api/v0/images")
@Produces(MediaType.APPLICATION_JSON)
public class ImageResource {

    @Inject ExternalImageService externalImageService;

    @GET
    @Path("/{hash}")
    @Produces(MediaType.WILDCARD)
    public Response getImage(@PathParam("hash") String hash, @QueryParam("thumb") boolean thumb) {
        var cached = externalImageService.getCachedImage(hash, thumb);
        if (cached.isEmpty()) {
            return Response.status(404).entity(Map.of("error", "not_found")).build();
        }
        return Response.ok(cached.get().data())
                .type(cached.get().contentType())
                .header("Cache-Control", "public, max-age=604800")
                .build();
    }
}
