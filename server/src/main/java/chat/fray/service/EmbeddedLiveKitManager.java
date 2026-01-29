package chat.fray.service;

import chat.fray.config.FrayFileConfig;
import chat.fray.config.FrayLiveKitConfig;
import chat.fray.db.DatabaseService;
import io.quarkus.runtime.Startup;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Startup
@ApplicationScoped
public class EmbeddedLiveKitManager {

    private static final Logger LOG = Logger.getLogger(EmbeddedLiveKitManager.class);

    private static final String CONFIG_KEY_API_KEY = "livekit_api_key";
    private static final String CONFIG_KEY_API_SECRET = "livekit_api_secret";
    private static final long MAX_BACKOFF_MS = 60_000;
    private static final long INITIAL_BACKOFF_MS = 1_000;

    @Inject FrayLiveKitConfig config;
    @Inject FrayFileConfig fileConfig;
    @Inject DatabaseService db;
    @Inject LiveKitService liveKitService;

    private Process process;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ScheduledExecutorService watchdog;
    private long currentBackoff = INITIAL_BACKOFF_MS;
    private String apiKey;
    private String apiSecret;
    private Path configPath;

    @PostConstruct
    void init() {
        if (!"embedded".equalsIgnoreCase(config.mode())) {
            return;
        }

        try {
            // Load or generate API key/secret
            apiKey = loadOrGenerate(CONFIG_KEY_API_KEY, 20);
            apiSecret = loadOrGenerate(CONFIG_KEY_API_SECRET, 32);

            // Generate config YAML
            configPath = Path.of(fileConfig.dataDir(), "livekit.yaml");
            writeConfigYaml();

            // Configure LiveKitService with embedded credentials
            String url = "ws://localhost:" + config.port();
            liveKitService.configure(url, apiKey, apiSecret);

            // Start process
            startProcess();

            // Health check
            if (waitForHealthy()) {
                LOG.info("[BOOT] LiveKit (embedded) ... OK");
                liveKitService.reconcileOnStartup();
            } else {
                LOG.warn("[BOOT] LiveKit (embedded) ... STARTED (health check pending)");
            }

            // Start watchdog for crash restarts
            watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "livekit-watchdog");
                t.setDaemon(true);
                return t;
            });
            watchdog.scheduleWithFixedDelay(this::checkProcess, 5, 5, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.errorf("[BOOT] LiveKit (embedded) ... FAIL: %s", e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() {
        running.set(false);
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
        stopProcess();
    }

    private void startProcess() throws IOException {
        var pb = new ProcessBuilder(config.path(), "--config", configPath.toString());
        pb.redirectErrorStream(true);
        process = pb.start();
        running.set(true);
        currentBackoff = INITIAL_BACKOFF_MS;

        // Drain stdout/stderr in background thread
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        Thread.ofVirtual().name("livekit-stdout").start(() -> {
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    LOG.debugf("[LiveKit] %s", line);
                }
            } catch (IOException ignored) {
            }
        });

        LOG.infof("LiveKit process started (pid=%d)", process.pid());
    }

    private void stopProcess() {
        if (process != null && process.isAlive()) {
            LOG.info("Stopping LiveKit process...");
            process.destroy(); // SIGTERM
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly(); // SIGKILL
                    LOG.warn("LiveKit process force-killed");
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void checkProcess() {
        if (!running.get() || process == null) return;
        if (process.isAlive()) {
            currentBackoff = INITIAL_BACKOFF_MS; // Reset backoff on healthy
            return;
        }

        LOG.warnf("LiveKit process exited (code=%d), restarting in %dms...",
                process.exitValue(), currentBackoff);
        try {
            Thread.sleep(currentBackoff);
            currentBackoff = Math.min(currentBackoff * 2, MAX_BACKOFF_MS);
            startProcess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            LOG.errorf("Failed to restart LiveKit: %s", e.getMessage());
        }
    }

    private boolean waitForHealthy() {
        String healthUrl = "http://localhost:" + config.port();
        for (int i = 0; i < 10; i++) {
            try {
                Thread.sleep(500);
                var conn = (HttpURLConnection) URI.create(healthUrl).toURL().openConnection();
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(1000);
                int code = conn.getResponseCode();
                conn.disconnect();
                if (code < 500) return true;
            } catch (Exception ignored) {
            }
        }
        return false;
    }

    private void writeConfigYaml() throws IOException {
        Files.createDirectories(configPath.getParent());

        var sb = new StringBuilder();
        sb.append("port: ").append(config.port()).append('\n');
        sb.append("rtc:\n");
        sb.append("  tcp_port: ").append(config.rtcTcpPort()).append('\n');
        sb.append("  port_range_start: ").append(config.rtcUdpStart()).append('\n');
        sb.append("  port_range_end: ").append(config.rtcUdpEnd()).append('\n');

        config.publicIp().filter(s -> !s.isBlank()).ifPresent(ip ->
                sb.append("  node_ip: ").append(ip).append('\n'));

        sb.append("keys:\n");
        sb.append("  ").append(apiKey).append(": ").append(apiSecret).append('\n');

        sb.append("room:\n");
        sb.append("  max_participants: ").append(config.maxParticipants()).append('\n');
        sb.append("  auto_create: true\n");

        if (config.turnEnabled()) {
            sb.append("turn:\n");
            sb.append("  enabled: true\n");
            sb.append("  udp_port: ").append(config.turnUdpPort()).append('\n');
        }

        // Webhook pointing back to Fray
        sb.append("webhook:\n");
        sb.append("  urls:\n");
        sb.append("    - http://localhost:8080/api/v0/webhooks/livekit\n");
        sb.append("  api_key: ").append(apiKey).append('\n');

        Files.writeString(configPath, sb.toString());
        LOG.debugf("Wrote LiveKit config to %s", configPath);
    }

    /** Load a value from server_config or generate + persist a new random string */
    private String loadOrGenerate(String configKey, int byteLength) {
        var rows = db.query("SELECT value FROM server_config WHERE key = ?", configKey);
        if (!rows.isEmpty()) {
            var value = (String) rows.getFirst().get("value");
            if (value != null && !value.isBlank()) return value;
        }

        byte[] bytes = new byte[byteLength];
        new SecureRandom().nextBytes(bytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        db.execute(
                "INSERT OR REPLACE INTO server_config (key, value, updated_at) VALUES (?, ?, datetime('now'))",
                configKey, encoded
        );
        LOG.infof("Generated LiveKit %s (persisted to server_config)", configKey);
        return encoded;
    }
}
