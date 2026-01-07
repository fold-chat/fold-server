package chat.fray.api;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Map;

@Path("/api/v0/status")
public class StatusResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, String> status() {
        return Map.of("status", "UP", "version", "0.1.0");
    }
}
