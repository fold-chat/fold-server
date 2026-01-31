package chat.kith.config;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class SpaFallbackHandler {

    void init(@Observes Router router) {
        // Fallback: serve index.html for non-API routes that don't match a static file
        router.route().last().handler(ctx -> {
            var path = ctx.request().path();
            if (path != null && !path.startsWith("/api/") && !path.startsWith("/q/")
                    && !"/index.html".equals(path)) {
                ctx.reroute("/index.html");
            } else {
                ctx.next();
            }
        });
    }
}
