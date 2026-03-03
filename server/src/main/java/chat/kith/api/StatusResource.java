package chat.kith.api;

import chat.kith.db.DatabaseService;
import chat.kith.service.MaintenanceService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v0/status")
public class StatusResource {

    @Inject DatabaseService db;
    @Inject MaintenanceService maintenanceService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        var result = new LinkedHashMap<String, Object>();
        result.put("status", maintenanceService.isEnabled() ? "MAINTENANCE" : "UP");
        result.put("version", "0.1.0");
        var rows = db.query("SELECT value FROM server_config WHERE key = 'server_name'");
        result.put("server_name", rows.isEmpty() ? "Kith" : rows.getFirst().get("value"));
        result.put("maintenance", maintenanceService.isEnabled());
        if (maintenanceService.isEnabled()) {
            result.put("maintenance_message", maintenanceService.getMessage());
        }
        return result;
    }
}
