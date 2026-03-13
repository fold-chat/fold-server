package chat.fold.config;

import io.vertx.ext.web.Router;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

@ApplicationScoped
public class CacheHeaderHandler {

    void init(@Observes Router router) {
        router.route().order(0).handler(ctx -> {
            var path = ctx.request().path();
            if (path != null && path.startsWith("/_app/immutable/")) {
                ctx.response().putHeader("Cache-Control", "public, max-age=31536000, immutable");
            } else if (path != null && !path.startsWith("/api/") && !path.startsWith("/q/")) {
                ctx.response().putHeader("Cache-Control", "no-cache");
            }
            ctx.next();
        });
    }
}
