package chat.fold.api;

import chat.fold.auth.FileService;
import chat.fold.auth.FoldSecurityContext;
import chat.fold.db.FileRepository;
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
import java.util.HashMap;
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
        var sc = (FoldSecurityContext) requestContext.getSecurityContext();

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
            var response = new HashMap<String, String>();
            response.put("id", result.get("id"));
            response.put("url", result.get("url"));
            response.put("processing_status", result.get("processing_status"));
            return Response.status(Response.Status.CREATED).entity(response).build();
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
            String mimeType = (String) meta.get("mime_type");

            // SVGs: force download, never render inline
            boolean isSvg = "image/svg+xml".equals(mimeType);
            String disposition = isSvg
                    ? "attachment; filename=\"" + originalName + "\""
                    : "inline; filename=\"" + originalName + "\"";

            return Response.ok(data)
                    .type(mimeType)
                    .header("Content-Disposition", disposition)
                    .header("Cache-Control", "public, max-age=31536000, immutable")
                    .header("Content-Length", data.length)
                    .header("Content-Security-Policy", "sandbox")
                    .build();
        } catch (IOException e) {
            return Response.status(500).build();
        }
    }

    @GET
    @Path("/files/{storedName}/thumbnail")
    public Response serveThumbnail(@PathParam("storedName") String storedName) {
        var fileMeta = fileRepo.findByStoredName(storedName);
        if (fileMeta.isEmpty()) {
            return Response.status(404).build();
        }

        var meta = fileMeta.get();
        String thumbName = (String) meta.get("thumbnail_stored_name");
        if (thumbName == null) {
            return Response.status(404).build();
        }

        var thumbPath = fileService.getFilePath(thumbName);
        if (thumbPath.isEmpty()) {
            return Response.status(404).build();
        }

        try {
            byte[] data = Files.readAllBytes(thumbPath.get());
            return Response.ok(data)
                    .type("image/jpeg")
                    .header("Cache-Control", "public, max-age=31536000, immutable")
                    .header("Content-Length", data.length)
                    .header("Content-Security-Policy", "sandbox")
                    .build();
        } catch (IOException e) {
            return Response.status(500).build();
        }
    }
}
