package chat.kith.livekit;

import io.jsonwebtoken.Jwts;

import javax.crypto.spec.SecretKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds LiveKit-compatible JWTs (HS256) for both participant join tokens
 * and server-to-server API tokens. Replaces io.livekit.server.AccessToken.
 */
public class LiveKitToken {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private String identity;
    private String name;
    private Duration ttl = DEFAULT_TTL;
    private final Map<String, Object> videoGrants = new LinkedHashMap<>();

    public LiveKitToken identity(String identity) {
        this.identity = identity;
        return this;
    }

    public LiveKitToken name(String name) {
        this.name = name;
        return this;
    }

    public LiveKitToken ttl(Duration ttl) {
        this.ttl = ttl;
        return this;
    }

    public LiveKitToken grant(String key, Object value) {
        this.videoGrants.put(key, value);
        return this;
    }

    public LiveKitToken roomJoin(boolean value) { return grant("roomJoin", value); }
    public LiveKitToken room(String room) { return grant("room", room); }
    public LiveKitToken canPublish(boolean value) { return grant("canPublish", value); }
    public LiveKitToken canSubscribe(boolean value) { return grant("canSubscribe", value); }
    public LiveKitToken roomList(boolean value) { return grant("roomList", value); }
    public LiveKitToken roomAdmin(boolean value) { return grant("roomAdmin", value); }

    public String toJwt(String apiKey, String apiSecret) {
        var now = Instant.now();
        var key = new SecretKeySpec(apiSecret.getBytes(), "HmacSHA256");
        var builder = Jwts.builder()
                .issuer(apiKey)
                .notBefore(Date.from(now))
                .expiration(Date.from(now.plus(ttl)));
        if (identity != null && !identity.isEmpty()) {
            builder.subject(identity);
        }
        if (name != null && !name.isEmpty()) {
            builder.claim("name", name);
        }
        if (!videoGrants.isEmpty()) {
            builder.claim("video", videoGrants);
        }
        return builder.signWith(key).compact();
    }

    /** Short-lived server-to-server token with the given video grants. */
    public static String serverToken(String apiKey, String apiSecret, Map<String, Object> grants) {
        var token = new LiveKitToken().ttl(DEFAULT_TTL);
        grants.forEach(token::grant);
        return token.toJwt(apiKey, apiSecret);
    }
}
