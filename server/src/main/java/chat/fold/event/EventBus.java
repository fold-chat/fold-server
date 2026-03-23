package chat.fold.event;

import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

@ApplicationScoped
public class EventBus {

    private static final Logger LOG = Logger.getLogger(EventBus.class);

    @Inject SessionRegistry registry;
    @Inject PermissionService permissionService;
    @Inject EventBuffer eventBuffer;

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong sequenceCounter = new AtomicLong(0);
    private final ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public void publish(Event event) {
        long seq = sequenceCounter.incrementAndGet();

        // Run the entire publish on a virtual thread — no Vert.x worker pool contention
        virtualExecutor.submit(() -> {
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

                // Buffer for active sessions
                var bufferedSessionIds = new HashSet<String>();
                for (var conn : targets) {
                    var sessionId = registry.getSessionId(conn);
                    if (sessionId != null && bufferedSessionIds.add(sessionId)) {
                        eventBuffer.append(sessionId, seq, json);
                    }
                }

                // Fan out sends — each send is independent I/O on its own virtual thread
                for (var conn : targets) {
                    conn.sendText(json).subscribe().with(
                            ok -> {},
                            err -> LOG.debugf("Error dispatching to %s: %s", conn.id(), err.getMessage())
                    );
                }

                // Buffer for suspended sessions
                var suspendedTargets = resolveSuspendedTargets(event.scope(), event.excludeUserId());
                for (var sessionId : suspendedTargets) {
                    if (bufferedSessionIds.add(sessionId)) {
                        eventBuffer.append(sessionId, seq, json);
                    }
                }
            } catch (Exception e) {
                LOG.errorf("Failed to serialize event %s: %s", event.type(), e.getMessage());
            }
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

    /** Resolve suspended session IDs that should receive events for the given scope. */
    private Set<String> resolveSuspendedTargets(Scope scope, String excludeUserId) {
        var suspended = registry.allSuspendedSessions();
        if (suspended.isEmpty()) return Set.of();

        var result = new HashSet<String>();
        for (var s : suspended) {
            if (excludeUserId != null && s.userId().equals(excludeUserId)) continue;
            if (!eventBuffer.hasBuffer(s.sessionId())) continue;

            boolean matches = switch (scope) {
                case Scope.Server ignored -> true;
                case Scope.Channel c -> permissionService.hasPermission(s.userId(), c.channelId(), Permission.VIEW_CHANNEL);
                case Scope.User u -> s.userId().equals(u.userId());
                case Scope.Users u -> u.userIds().contains(s.userId());
            };
            if (matches) result.add(s.sessionId());
        }
        return result;
    }
}
