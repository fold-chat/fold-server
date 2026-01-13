package chat.fray.event;

import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class SessionRegistry {

    // userId → set of active connections
    private final Map<String, Set<WebSocketConnection>> sessions = new ConcurrentHashMap<>();
    // connectionId → userId (reverse lookup)
    private final Map<String, String> connectionToUser = new ConcurrentHashMap<>();
    // connectionId → session metadata
    private final Map<String, SessionMeta> sessionMeta = new ConcurrentHashMap<>();

    public record SessionMeta(String userId, String username, String sessionId, long connectedAt) {}

    public void register(String userId, String username, WebSocketConnection connection) {
        sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(connection);
        System.out.println(sessions.size());
        connectionToUser.put(connection.id(), userId);
        sessionMeta.put(connection.id(), new SessionMeta(userId, username, connection.id(), System.currentTimeMillis()));
    }

    public void unregister(WebSocketConnection connection) {
        var userId = connectionToUser.remove(connection.id());
        sessionMeta.remove(connection.id());
        if (userId != null) {
            var conns = sessions.get(userId);
            if (conns != null) {
                conns.remove(connection);
                if (conns.isEmpty()) sessions.remove(userId);
            }
        }
    }

    public String getUserId(WebSocketConnection connection) {
        return connectionToUser.get(connection.id());
    }

    public SessionMeta getMeta(WebSocketConnection connection) {
        return sessionMeta.get(connection.id());
    }

    /** Get all connections for a user */
    public Set<WebSocketConnection> getConnections(String userId) {
        return sessions.getOrDefault(userId, Set.of());
    }

    /** Get all active connections */
    public Collection<WebSocketConnection> allConnections() {
        var all = new ArrayList<WebSocketConnection>();
        sessions.values().forEach(all::addAll);
        return all;
    }

    /** Get all connected user IDs */
    public Set<String> onlineUserIds() {
        return Collections.unmodifiableSet(sessions.keySet());
    }

    public boolean isOnline(String userId) {
        var conns = sessions.get(userId);
        return conns != null && !conns.isEmpty();
    }
}
