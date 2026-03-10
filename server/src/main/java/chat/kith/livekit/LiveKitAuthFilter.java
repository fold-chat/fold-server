package chat.kith.livekit;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;

import java.util.Map;

/**
 * Injects Authorization: Bearer header with a server-to-server LiveKit JWT.
 * Generates a fresh token per request with roomList + roomAdmin grants.
 */
public class LiveKitAuthFilter implements ClientRequestFilter {

    private static final Map<String, Object> SERVER_GRANTS = Map.of(
            "roomList", true,
            "roomAdmin", true,
            "roomCreate", true
    );

    private final String apiKey;
    private final String apiSecret;

    public LiveKitAuthFilter(String apiKey, String apiSecret) {
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    @Override
    public void filter(ClientRequestContext ctx) {
        String jwt = LiveKitToken.serverToken(apiKey, apiSecret, SERVER_GRANTS);
        ctx.getHeaders().putSingle("Authorization", "Bearer " + jwt);
    }
}
