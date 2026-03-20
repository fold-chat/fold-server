package chat.fold.api;

import chat.fold.db.DatabaseService;
import chat.fold.service.BackupService;
import chat.fold.service.MaintenanceService;
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
    @Inject BackupService backupService;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status() {
        if (backupService.isRestartRequired()) {
            var result = new LinkedHashMap<String, Object>();
            result.put("status", "RESTART_REQUIRED");
            result.put("restart_required", true);
            result.put("message", "Backup restored. Please restart the server.");
            return result;
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("status", maintenanceService.isEnabled() ? "MAINTENANCE" : "UP");
        result.put("version", "0.1.0");
        var rows = db.query("SELECT value FROM server_config WHERE key = 'server_name'");
        result.put("server_name", rows.isEmpty() ? "Fold" : rows.getFirst().get("value"));
        var iconRows = db.query("SELECT value FROM server_config WHERE key = 'server_icon'");
        boolean hasIcon = !iconRows.isEmpty() && iconRows.getFirst().get("value") != null;
        result.put("server_icon_url", hasIcon ? "/api/v0/settings/icon" : null);
        result.put("maintenance", maintenanceService.isEnabled());
        if (maintenanceService.isEnabled()) {
            result.put("maintenance_message", maintenanceService.getMessage());
        }
        result.put("restart_required", false);
        return result;
    }
}
