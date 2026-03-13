package chat.fold.security;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.util.Map;

@Provider
public class PermissionExceptionMapper implements ExceptionMapper<PermissionService.PermissionException> {

    @Override
    public Response toResponse(PermissionService.PermissionException e) {
        return Response.status(403)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of(
                        "error", "forbidden",
                        "message", "Missing permission: " + e.permission.name()
                ))
                .build();
    }
}
