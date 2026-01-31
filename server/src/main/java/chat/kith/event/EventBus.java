package chat.kith.event;

import chat.kith.security.Permission;
import chat.kith.security.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.WebSocketConnection;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class EventBus {

    private static final Logger LOG = Logger.getLogger(EventBus.class);

    @Inject SessionRegistry registry;
    @Inject PermissionService permissionService;
    @Inject Vertx vertx;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong sequenceCounter = new AtomicLong(0);

    public void publish(Event event) {
        long seq = sequenceCounter.incrementAndGet();

        vertx.executeBlocking(() -> {
            Collection<WebSocketConnection> targets = resolveTargets(event.scope());
                if (event.excludeUserId() != null) {
                    var excluded = registry.getConnections(event.excludeUserId());
                    targets = targets.stream().filter(c -> !excluded.contains(c)).toList();
                }
            try {
                String json = mapper.writeValueAsString(Map.of(
                        "op", event.type().name(),
                        "d", event.data(),
                        "s", seq
                ));
                for (var conn : targets) {
                    vertx.runOnContext(v ->
                        conn.sendText(json).subscribe().with(
                                ok -> {},
                                err -> LOG.debugf("Error dispatching to %s: %s", conn.id(), err.getMessage())
                        )
                    );
                }
            } catch (Exception e) {
                LOG.errorf("Failed to serialize event %s: %s", event.type(), e.getMessage());
            }
            return null;
        });
    }

    private Collection<WebSocketConnection> resolveTargets(Scope scope) {
        return switch (scope) {
            case Scope.Server s -> registry.allConnections();
            case Scope.Channel c -> {
                var all = registry.allConnections();
                yield all.stream()
                        .filter(conn -> {
                            var userId = registry.getUserId(conn);
                            return userId != null && permissionService.hasPermission(userId, c.channelId(), Permission.VIEW_CHANNEL);
                        })
                        .toList();
            }
            case Scope.User u -> registry.getConnections(u.userId());
            case Scope.Users u -> {
                var conns = new java.util.ArrayList<WebSocketConnection>();
                u.userIds().forEach(id -> conns.addAll(registry.getConnections(id)));
                yield conns;
            }
        };
    }
}
