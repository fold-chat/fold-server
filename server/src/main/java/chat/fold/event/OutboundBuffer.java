package chat.fold.event;

import io.quarkus.scheduler.Scheduled;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-connection sequential write queue. Ensures only one sendText() is
 * in-flight per connection at a time, providing natural backpressure.
 * No protocol changes — events are sent individually in order.
 */
@ApplicationScoped
public class OutboundBuffer {

    private static final Logger LOG = Logger.getLogger(OutboundBuffer.class);
    private static final int SLOW_CONSUMER_THRESHOLD = 500;

    private final Map<String, ConnectionWriter> writers = new ConcurrentHashMap<>();

    // --- Metrics ---
    private final AtomicLong totalSends = new AtomicLong();
    private final AtomicLong totalDropped = new AtomicLong();
    private final AtomicLong slowConsumerKills = new AtomicLong();

    static class ConnectionWriter {
        final WebSocketConnection connection;
        final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
        final AtomicBoolean draining = new AtomicBoolean(false);
        volatile boolean dead = false;

        ConnectionWriter(WebSocketConnection connection) {
            this.connection = connection;
        }
    }

    /** Enqueue a message and start sequential drain if not already running. */
    public void enqueue(WebSocketConnection conn, String json) {
        var writer = writers.computeIfAbsent(conn.id(), id -> new ConnectionWriter(conn));
        if (writer.dead) return; // connection marked for closure, discard silently
        writer.queue.add(json);

        // Slow consumer — if queue too deep, mark dead and close
        if (writer.queue.size() > SLOW_CONSUMER_THRESHOLD) {
            if (writer.dead) return; // already being killed
            writer.dead = true;
            slowConsumerKills.incrementAndGet();
            totalDropped.addAndGet(writer.queue.size());
            LOG.warnf("Slow consumer: conn=%s queue=%d, closing", conn.id(), writer.queue.size());
            writer.queue.clear();
            try {
                conn.close().subscribe().with(v -> {}, e -> {});
            } catch (Exception e) {
                // ignore
            }
            return;
        }

        tryDrain(writer);
    }

    /** Remove a connection's writer (call on disconnect). */
    public void remove(String connectionId) {
        var writer = writers.remove(connectionId);
        if (writer != null) writer.queue.clear();
    }

    private void tryDrain(ConnectionWriter writer) {
        if (!writer.draining.compareAndSet(false, true)) return;
        drainNext(writer);
    }

    private void drainNext(ConnectionWriter writer) {
        if (writer.dead) {
            writer.draining.set(false);
            return;
        }
        var msg = writer.queue.poll();
        if (msg == null) {
            writer.draining.set(false);
            // Re-check: something may have been enqueued between poll and set
            if (!writer.dead && !writer.queue.isEmpty() && writer.draining.compareAndSet(false, true)) {
                drainNext(writer);
            }
            return;
        }

        totalSends.incrementAndGet();

        try {
            writer.connection.sendText(msg).subscribe().with(
                    ok -> drainNext(writer),
                    err -> {
                        LOG.debugf("Write error conn=%s: %s", writer.connection.id(), err.getMessage());
                        drainNext(writer);
                    }
            );
        } catch (Exception e) {
            writer.queue.clear();
            writer.draining.set(false);
            writers.remove(writer.connection.id());
        }
    }

    @Scheduled(every = "10s")
    void logMetrics() {
        long sends = totalSends.getAndSet(0);
        long dropped = totalDropped.getAndSet(0);
        long kills = slowConsumerKills.getAndSet(0);

        if (sends == 0 && kills == 0) return;

        int maxQueue = 0;
        for (var w : writers.values()) {
            maxQueue = Math.max(maxQueue, w.queue.size());
        }

        LOG.infof("WS [10s] sends=%d dropped=%d slowKills=%d conns=%d maxQueue=%d",
                sends, dropped, kills, writers.size(), maxQueue);
    }
}
