package chat.kith.api;

import chat.kith.auth.FileService;
import chat.kith.auth.KithSecurityContext;
import chat.kith.db.FileRepository;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.jboss.resteasy.reactive.RestForm;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

@Path("/api/v0")
@Produces(MediaType.APPLICATION_JSON)
public class FileResource {

    @Inject FileService fileService;
    @Inject FileRepository fileRepo;
    @Context ContainerRequestContext requestContext;

    @POST
    @Path("/upload")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response upload(@RestForm("file") FileUpload file) {
        var sc = (KithSecurityContext) requestContext.getSecurityContext();

        if (file == null) {
            return Response.status(400).entity(Map.of("error", "missing_file")).build();
        }

        try {
            var inputStream = Files.newInputStream(file.uploadedFile());
            var result = fileService.upload(
                    file.fileName(),
                    file.contentType(),
                    Files.size(file.uploadedFile()),
                    inputStream,
                    sc.getUserId()
            );
            return Response.status(Response.Status.CREATED)
                    .entity(Map.of("id", result.get("id"), "url", result.get("url")))
                    .build();
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(Map.of("error", "invalid_file", "message", e.getMessage())).build();
        } catch (IOException e) {
            return Response.status(500).entity(Map.of("error", "upload_failed")).build();
        }
    }

    @GET
    @Path("/files/{storedName}")
    public Response serve(@PathParam("storedName") String storedName) {
        var fileMeta = fileRepo.findByStoredName(storedName);
        if (fileMeta.isEmpty()) {
            return Response.status(404).build();
        }

        var meta = fileMeta.get();
        var filePath = fileService.getFilePath(storedName);
        if (filePath.isEmpty()) {
            return Response.status(404).build();
        }

        try {
            byte[] data = Files.readAllBytes(filePath.get());
            String originalName = (String) meta.get("original_name");
            return Response.ok(data)
                    .type((String) meta.get("mime_type"))
                    .header("Content-Disposition", "inline; filename=\"" + originalName + "\"")
                    .header("Cache-Control", "public, max-age=31536000, immutable")
                    .header("Content-Length", data.length)
                    .build();
        } catch (IOException e) {
            return Response.status(500).build();
        }
    }
}
