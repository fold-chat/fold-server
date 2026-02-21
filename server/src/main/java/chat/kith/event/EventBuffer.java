package chat.kith.event;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-session ring buffer for WebSocket event replay.
 * Keeps up to MAX_EVENTS per session with a TTL of BUFFER_TTL_MS.
 * Expired buffers are cleaned up every 60 seconds.
 */
@ApplicationScoped
public class EventBuffer {

    private static final Logger LOG = Logger.getLogger(EventBuffer.class);
    static final int MAX_EVENTS = 1000;
    static final long BUFFER_TTL_MS = 5 * 60 * 1000; // 5 minutes

    public record BufferedEvent(long sequence, String json, long timestampMs) {}

    static class SessionBuffer {
        private final String userId;
        private final long createdAt;
        private final ArrayDeque<BufferedEvent> events = new ArrayDeque<>();

        SessionBuffer(String userId) {
            this.userId = userId;
            this.createdAt = System.currentTimeMillis();
        }

        synchronized void append(long sequence, String json) {
            if (events.size() >= MAX_EVENTS) {
                events.pollFirst();
            }
            events.addLast(new BufferedEvent(sequence, json, System.currentTimeMillis()));
        }

        synchronized List<BufferedEvent> eventsSince(long lastSequence) {
            var result = new ArrayList<BufferedEvent>();
            for (var e : events) {
                if (e.sequence() > lastSequence) {
                    result.add(e);
                }
            }
            return result;
        }

        synchronized boolean isExpired(long now) {
            // Buffer is expired if it was created more than TTL ago and has no recent events,
            // or if the newest event is older than TTL
            if (events.isEmpty()) {
                return now - createdAt > BUFFER_TTL_MS;
            }
            return now - events.peekLast().timestampMs() > BUFFER_TTL_MS;
        }

        synchronized boolean hasSequence(long sequence) {
            // Check if the requested sequence is still in the buffer
            if (events.isEmpty()) return sequence == 0;
            return events.peekFirst().sequence() <= sequence + 1;
        }

        String userId() { return userId; }
    }

    private final Map<String, SessionBuffer> buffers = new ConcurrentHashMap<>();

    /** Create a buffer for a new session. */
    public void createBuffer(String sessionId, String userId) {
        buffers.put(sessionId, new SessionBuffer(userId));
    }

    /** Append an event to a session's buffer. */
    public void append(String sessionId, long sequence, String json) {
        var buf = buffers.get(sessionId);
        if (buf != null) {
            buf.append(sequence, json);
        }
    }

    /**
     * Get all events after the given sequence number for a session.
     * Returns null if the session buffer doesn't exist or the sequence is too old (gap in buffer).
     */
    public List<BufferedEvent> eventsSince(String sessionId, long lastSequence) {
        var buf = buffers.get(sessionId);
        if (buf == null) return null;
        if (!buf.hasSequence(lastSequence)) return null; // sequence fell off the buffer
        return buf.eventsSince(lastSequence);
    }

    /** Get the userId associated with a session buffer. */
    public String getUserId(String sessionId) {
        var buf = buffers.get(sessionId);
        return buf != null ? buf.userId() : null;
    }

    /** Check if a buffer exists for the given session. */
    public boolean hasBuffer(String sessionId) {
        return buffers.containsKey(sessionId);
    }

    /** Remove a session's buffer. */
    public void removeBuffer(String sessionId) {
        buffers.remove(sessionId);
    }

    /** Get all session IDs that have active buffers. */
    public Set<String> allSessionIds() {
        return Collections.unmodifiableSet(buffers.keySet());
    }

    @Scheduled(every = "60s")
    void cleanExpired() {
        long now = System.currentTimeMillis();
        var it = buffers.entrySet().iterator();
        int removed = 0;
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().isExpired(now)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            LOG.debugf("Cleaned %d expired event buffers", removed);
        }
    }

    // Visible for testing
    int bufferCount() {
        return buffers.size();
    }
}
