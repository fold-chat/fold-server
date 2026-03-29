package chat.fold.config;

import chat.fold.service.LiveKitService;
import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

@ApplicationScoped
public class SecurityHeaderHandler {

    @Inject
    LiveKitService liveKitService;

    void init(@Observes Router router) {
        router.route().order(-1).handler(ctx -> {
            var response = ctx.response();
            var path = ctx.request().path();

            response.putHeader("X-Content-Type-Options", "nosniff");
            response.putHeader("X-Frame-Options", "DENY");
            response.putHeader("X-XSS-Protection", "0");
            response.putHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            response.putHeader("Permissions-Policy", "camera=(self), microphone=(self), geolocation=(), payment=()");

            // Skip global CSP on endpoints that set their own more-restrictive CSP
            boolean hasDedicatedCsp = path != null && (
                    path.startsWith("/api/v0/files/") ||
                    path.startsWith("/api/v0/avatars/default/") ||
                    path.equals("/api/v0/settings/icon")
            );
            if (!hasDedicatedCsp) {
                // CSP built per-request: LiveKit mode and URL can change at runtime
                response.putHeader("Content-Security-Policy", buildCsp());
            }

            ctx.next();
        });
    }

    private String buildCsp() {
        return "default-src 'self'; " +
               "script-src 'self'; " +
               "style-src 'self' 'unsafe-inline'; " +
               "img-src 'self' data: blob:; " +
               "font-src 'self' data:; " +
               "connect-src " + buildConnectSrc() + "; " +
               "media-src 'self' blob:; " +
               "object-src 'none'; " +
               "base-uri 'self'; " +
               "form-action 'self'; " +
               "frame-ancestors 'none'";
    }

    private String buildConnectSrc() {
        String mode = liveKitService.getMode();
        return switch (mode) {
            case "embedded" -> "'self' wss:";
            case "external", "managed" -> {
                String url = liveKitService.getUrl();
                if (url == null || url.isBlank()) yield "'self'";
                // Include both the HTTP(S) and WS(S) variants the browser needs
                yield "'self' " + url + " " + toWsVariant(url);
            }
            default -> "'self'";  // off
        };
    }

    /** Converts https:// ↔ wss:// or http:// ↔ ws:// */
    private static String toWsVariant(String url) {
        if (url.startsWith("https://")) return "wss://" + url.substring(8);
        if (url.startsWith("http://"))  return "ws://"  + url.substring(7);
        if (url.startsWith("wss://"))   return "https://" + url.substring(6);
        if (url.startsWith("ws://"))    return "http://"  + url.substring(5);
        return url;
    }
}
