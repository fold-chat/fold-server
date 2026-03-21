package chat.fold.api;

import chat.fold.config.BuildInfo;
import chat.fold.db.DatabaseService;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v0/info")
@Produces(MediaType.APPLICATION_JSON)
public class InfoResource {

    @Inject DatabaseService db;

    /** Public, unauthenticated. Returns server version + basic metadata. */
    @GET
    public Map<String, Object> getInfo() {
        var result = new LinkedHashMap<String, Object>();
        result.put("version", BuildInfo.VERSION);

        var rows = db.query("SELECT key, value FROM server_config WHERE key IN ('server_name', 'server_icon')");
        String name = null;
        String iconUrl = null;
        for (var row : rows) {
            String key = (String) row.get("key");
            if ("server_name".equals(key)) name = (String) row.get("value");
            if ("server_icon".equals(key)) iconUrl = (String) row.get("value");
        }
        result.put("name", name != null ? name : "Fold");
        result.put("icon_url", iconUrl);
        return result;
    }
}
