package chat.fold.auth;

import chat.fold.config.FoldRateLimitConfig;
import chat.fold.config.RuntimeConfigService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class RateLimitService {

    @Inject
    FoldRateLimitConfig config;

    @Inject
    RuntimeConfigService runtimeConfig;

    private final ConcurrentHashMap<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /**
     * Check rate limit for given key and policy.
     * Key convention: "ip:<addr>:<action>" for unauthenticated, "user:<id>:<action>" for authenticated.
     */
    public RateLimitResult check(String key, RateLimitPolicy policy) {
        if (!config.enabled()) {
            return RateLimitResult.allowed(policy.maxTokens(), policy.maxTokens());
        }

        var bucket = buckets.computeIfAbsent(key, k -> new TokenBucket(policy));
        return bucket.tryConsume(policy);
    }

    /** Resolve effective policy: runtime config (DB) → env config → hardcoded default */
    public RateLimitPolicy resolvePolicy(String action, RateLimitPolicy defaultPolicy) {
        // 1. Check runtime config (DB-backed, admin-overridable)
        String key = "fold.rate-limit." + action.replace("_", "-");
        String runtimeVal = runtimeConfig.getString(key, null);
        if (runtimeVal != null && !runtimeVal.isBlank()) {
            return RateLimitPolicy.parse(runtimeVal);
        }

        // 2. Check env-based config (@ConfigMapping)
        var envOverride = switch (action) {
            case "login" -> config.login();
            case "register" -> config.register();
            case "invite_join" -> config.inviteJoin();
            case "refresh" -> config.refresh();
            case "profile_update" -> config.profileUpdate();
            case "password_change" -> config.passwordChange();
            case "search" -> config.search();
            case "media_search" -> config.mediaSearch();
            case "voice_token" -> config.voiceToken();
            case "voice_state" -> config.voiceState();
            case "voice_moderation" -> config.voiceModeration();
            default -> java.util.Optional.<String>empty();
        };
        return envOverride.filter(s -> !s.isBlank()).map(RateLimitPolicy::parse).orElse(defaultPolicy);
    }

    @Scheduled(every = "5m")
    void cleanupStale() {
        var now = Instant.now();
        buckets.entrySet().removeIf(entry -> {
            var bucket = entry.getValue();
            var staleDuration = bucket.policy.window().multipliedBy(2);
            return Duration.between(bucket.lastAccess, now).compareTo(staleDuration) > 0;
        });
    }

    static class TokenBucket {
        final RateLimitPolicy policy;
        int tokens;
        Instant lastRefill;
        Instant lastAccess;

        TokenBucket(RateLimitPolicy policy) {
            this.policy = policy;
            this.tokens = policy.maxTokens();
            this.lastRefill = Instant.now();
            this.lastAccess = Instant.now();
        }

        synchronized RateLimitResult tryConsume(RateLimitPolicy policy) {
            refill(policy);
            lastAccess = Instant.now();

            if (tokens > 0) {
                tokens--;
                return RateLimitResult.allowed(policy.maxTokens(), tokens);
            }

            var elapsed = Duration.between(lastRefill, Instant.now());
            var retryAfter = policy.window().minus(elapsed);
            if (retryAfter.isNegative()) retryAfter = Duration.ZERO;
            return RateLimitResult.denied(policy.maxTokens(), retryAfter);
        }

        private void refill(RateLimitPolicy policy) {
            var now = Instant.now();
            var elapsed = Duration.between(lastRefill, now);
            if (elapsed.compareTo(policy.window()) >= 0) {
                tokens = policy.maxTokens();
                lastRefill = now;
            }
        }
    }
}
