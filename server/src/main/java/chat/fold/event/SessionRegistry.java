package chat.fold.event;

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
    // connectionId → sessionId
    private final Map<String, String> connectionToSessionId = new ConcurrentHashMap<>();
    // sessionId → suspended session info (for sessions that disconnected but may resume)
    private final Map<String, SuspendedSession> suspendedSessions = new ConcurrentHashMap<>();

    public record SessionMeta(String userId, String username, String sessionId, long connectedAt, boolean isBot) {}
    public record SuspendedSession(String userId, String username, String sessionId, long suspendedAt, boolean isBot) {}

    public void register(String userId, String username, String sessionId, WebSocketConnection connection) {
        register(userId, username, sessionId, connection, false);
    }

    public void register(String userId, String username, String sessionId, WebSocketConnection connection, boolean isBot) {
        sessions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(connection);
        connectionToUser.put(connection.id(), userId);
        connectionToSessionId.put(connection.id(), sessionId);
        sessionMeta.put(connection.id(), new SessionMeta(userId, username, sessionId, System.currentTimeMillis(), isBot));
    }

    /**
     * Suspend a connection's session (on disconnect). Moves it to the suspended map
     * so events can continue to be buffered for it.
     */
    public void suspend(WebSocketConnection connection) {
        var meta = sessionMeta.get(connection.id());
        var sid = connectionToSessionId.get(connection.id());
        // Unregister the connection from active maps
        var userId = connectionToUser.remove(connection.id());
        sessionMeta.remove(connection.id());
        connectionToSessionId.remove(connection.id());
        if (userId != null) {
            var conns = sessions.get(userId);
            if (conns != null) {
                conns.remove(connection);
                if (conns.isEmpty()) sessions.remove(userId);
            }
        }
        // Add to suspended if we have session info
        if (meta != null && sid != null) {
            suspendedSessions.put(sid, new SuspendedSession(
                    meta.userId(), meta.username(), sid, System.currentTimeMillis(), meta.isBot()));
        }
    }

    /**
     * Resume a suspended session with a new connection.
     * Returns the SuspendedSession if found and resumed, null otherwise.
     */
    public SuspendedSession resume(String sessionId, WebSocketConnection connection) {
        return resume(sessionId, connection, false);
    }

    public SuspendedSession resume(String sessionId, WebSocketConnection connection, boolean isBot) {
        var suspended = suspendedSessions.remove(sessionId);
        if (suspended == null) return null;
        register(suspended.userId(), suspended.username(), sessionId, connection, isBot);
        return suspended;
    }

    /** Unregister without suspending (for explicit disconnects like bans, auth failures). */
    public void unregister(WebSocketConnection connection) {
        var userId = connectionToUser.remove(connection.id());
        sessionMeta.remove(connection.id());
        connectionToSessionId.remove(connection.id());
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

    /** Get the session ID for a connection. */
    public String getSessionId(WebSocketConnection connection) {
        return connectionToSessionId.get(connection.id());
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

    /** Get all suspended sessions (for event buffering during publish). */
    public Collection<SuspendedSession> allSuspendedSessions() {
        return Collections.unmodifiableCollection(suspendedSessions.values());
    }

    /** Remove a suspended session (e.g. on buffer expiry). */
    public void removeSuspended(String sessionId) {
        suspendedSessions.remove(sessionId);
    }

    /** Clean up suspended sessions older than the given TTL. */
    public void cleanSuspendedOlderThan(long ttlMs) {
        long now = System.currentTimeMillis();
        suspendedSessions.entrySet().removeIf(e -> now - e.getValue().suspendedAt() > ttlMs);
    }
}
