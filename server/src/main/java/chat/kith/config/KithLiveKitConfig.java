package chat.kith.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

@ConfigMapping(prefix = "kith.livekit")
public interface KithLiveKitConfig {

    /** off (default), embedded, external */
    @WithDefault("off")
    String mode();

    /** LiveKit WS URL (required for external, auto-set for embedded) */
    Optional<String> url();

    /** LiveKit API key (required for external, auto-generated for embedded) */
    Optional<String> apiKey();

    /** LiveKit API secret (required for external, auto-generated for embedded) */
    Optional<String> apiSecret();

    // --- Embedded mode config ---

    /** Path to LiveKit binary (default: livekit-server on PATH) */
    @WithDefault("livekit-server")
    String path();

    /** LiveKit WS port */
    @WithDefault("7880")
    int port();

    /** RTC TCP fallback port */
    @WithDefault("7881")
    int rtcTcpPort();

    /** UDP port range start */
    @WithDefault("50000")
    int rtcUdpStart();

    /** UDP port range end */
    @WithDefault("60000")
    int rtcUdpEnd();

    /** Enable embedded TURN */
    @WithDefault("false")
    boolean turnEnabled();

    /** TURN UDP port */
    @WithDefault("3478")
    int turnUdpPort();

    /** Per-room participant limit */
    @WithDefault("50")
    int maxParticipants();

    /** Public IP for NAT traversal (maps to node_ip in LiveKit config) */
    Optional<String> publicIp();

    // --- Webhook ---

    /** Webhook secret (defaults to api-secret if not set) */
    Optional<String> webhookSecret();
}
