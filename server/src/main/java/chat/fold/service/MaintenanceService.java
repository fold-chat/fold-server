package chat.fold.service;

import chat.fold.db.DatabaseService;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MaintenanceService {

    private static final Logger LOG = Logger.getLogger(MaintenanceService.class);

    private static final String KEY_ENABLED = "maintenance_enabled";
    private static final String KEY_MESSAGE = "maintenance_message";

    @Inject DatabaseService db;

    private volatile boolean enabled;
    private volatile String message;

    @PostConstruct
    void init() {
        var rows = db.query("SELECT key, value FROM server_config WHERE key IN (?, ?)", KEY_ENABLED, KEY_MESSAGE);
        for (var row : rows) {
            String key = (String) row.get("key");
            String value = (String) row.get("value");
            if (KEY_ENABLED.equals(key)) {
                enabled = "true".equals(value);
            } else if (KEY_MESSAGE.equals(key)) {
                message = value;
            }
        }
        if (enabled) {
            LOG.infof("Server started in maintenance mode: %s", message != null ? message : "(no message)");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getMessage() {
        return message;
    }

    public void enable(String maintenanceMessage) {
        this.enabled = true;
        this.message = maintenanceMessage;
        db.execute("INSERT OR REPLACE INTO server_config (key, value, updated_at) VALUES (?, 'true', datetime('now'))", KEY_ENABLED);
        db.execute("INSERT OR REPLACE INTO server_config (key, value, updated_at) VALUES (?, ?, datetime('now'))", KEY_MESSAGE, maintenanceMessage);
        LOG.infof("Maintenance mode enabled: %s", maintenanceMessage != null ? maintenanceMessage : "(no message)");
    }

    public void disable() {
        this.enabled = false;
        this.message = null;
        db.execute("INSERT OR REPLACE INTO server_config (key, value, updated_at) VALUES (?, 'false', datetime('now'))", KEY_ENABLED);
        db.execute("INSERT OR REPLACE INTO server_config (key, value, updated_at) VALUES (?, NULL, datetime('now'))", KEY_MESSAGE);
        LOG.info("Maintenance mode disabled");
    }
}
