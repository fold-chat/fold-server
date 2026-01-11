package chat.fray.auth;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Adds rate limit headers to responses when set by endpoint logic.
 * Endpoints store RateLimitResult as a request property; this filter reads it.
 */
@Provider
public class RateLimitFilter implements ContainerResponseFilter {

    public static final String RATE_LIMIT_RESULT_KEY = "fray.rateLimit.result";

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        var result = (RateLimitResult) request.getProperty(RATE_LIMIT_RESULT_KEY);
        if (result == null) return;

        response.getHeaders().putSingle("X-RateLimit-Limit", result.limit());
        response.getHeaders().putSingle("X-RateLimit-Remaining", result.remaining());
        response.getHeaders().putSingle("X-RateLimit-Reset", result.retryAfter().toSeconds());
    }
}
