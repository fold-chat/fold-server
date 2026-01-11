package chat.fray.auth;

import java.time.Duration;

public record RateLimitResult(boolean allowed, int limit, int remaining, Duration retryAfter) {

    public static RateLimitResult allowed(int limit, int remaining) {
        return new RateLimitResult(true, limit, remaining, Duration.ZERO);
    }

    public static RateLimitResult denied(int limit, Duration retryAfter) {
        return new RateLimitResult(false, limit, 0, retryAfter);
    }
}
