package chat.fold.auth;

import java.time.Duration;

public record RateLimitPolicy(int maxTokens, Duration window) {

    public static final RateLimitPolicy LOGIN = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy REGISTER = new RateLimitPolicy(3, Duration.ofHours(1));
    public static final RateLimitPolicy INVITE_JOIN = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy REFRESH = new RateLimitPolicy(10, Duration.ofMinutes(1));
    public static final RateLimitPolicy PROFILE_UPDATE = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy PASSWORD_CHANGE = new RateLimitPolicy(3, Duration.ofHours(1));
    public static final RateLimitPolicy MESSAGE_SEND = new RateLimitPolicy(10, Duration.ofSeconds(10));
    public static final RateLimitPolicy MESSAGE_EDIT = new RateLimitPolicy(5, Duration.ofSeconds(10));
    public static final RateLimitPolicy MESSAGE_DELETE = new RateLimitPolicy(5, Duration.ofSeconds(10));
    public static final RateLimitPolicy CHANNEL_CREATE = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy CHANNEL_EDIT = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy CHANNEL_DELETE = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy THREAD_CREATE = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy SEARCH = new RateLimitPolicy(10, Duration.ofMinutes(1));
    public static final RateLimitPolicy REACTION_ADD = new RateLimitPolicy(10, Duration.ofSeconds(10));
    public static final RateLimitPolicy REACTION_REMOVE = new RateLimitPolicy(10, Duration.ofSeconds(10));
    public static final RateLimitPolicy MEDIA_SEARCH = new RateLimitPolicy(20, Duration.ofMinutes(1));
    public static final RateLimitPolicy VOICE_TOKEN = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy VOICE_STATE = new RateLimitPolicy(10, Duration.ofSeconds(10));
    public static final RateLimitPolicy VOICE_MODERATION = new RateLimitPolicy(10, Duration.ofMinutes(1));
    public static final RateLimitPolicy EMOJI_UPLOAD = new RateLimitPolicy(5, Duration.ofMinutes(1));
    public static final RateLimitPolicy EMOJI_DELETE = new RateLimitPolicy(5, Duration.ofMinutes(1));

    // Bot policies — higher limits for programmatic, multi-channel usage
    public static final RateLimitPolicy BOT_MESSAGE_SEND   = new RateLimitPolicy(60, Duration.ofSeconds(10));
    public static final RateLimitPolicy BOT_MESSAGE_EDIT   = new RateLimitPolicy(30, Duration.ofSeconds(10));
    public static final RateLimitPolicy BOT_MESSAGE_DELETE = new RateLimitPolicy(30, Duration.ofSeconds(10));
    public static final RateLimitPolicy BOT_REACTION_ADD   = new RateLimitPolicy(30, Duration.ofSeconds(10));
    public static final RateLimitPolicy BOT_REACTION_REMOVE= new RateLimitPolicy(30, Duration.ofSeconds(10));
    public static final RateLimitPolicy BOT_THREAD_CREATE  = new RateLimitPolicy(30, Duration.ofMinutes(1));

    /** Parse from string format "count/windowSeconds", e.g. "5/60" */
    public static RateLimitPolicy parse(String spec) {
        var parts = spec.split("/");
        return new RateLimitPolicy(Integer.parseInt(parts[0]), Duration.ofSeconds(Long.parseLong(parts[1])));
    }
}
