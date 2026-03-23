package chat.fold.service;

import chat.fold.db.DatabaseService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Buffers read state updates in memory and flushes to DB periodically.
 * Multiple updates for the same (userId, channelId) are coalesced — only the
 * latest lastReadMessageId is written. Flushes as a single batch SQL statement
 * to minimize write lock contention.
 */
@ApplicationScoped
public class ReadStateBuffer {

    private static final Logger LOG = Logger.getLogger(ReadStateBuffer.class);

    @Inject DatabaseService db;

    // Key: "userId:channelId" → latest lastReadMessageId
    private final ConcurrentHashMap<String, String> pending = new ConcurrentHashMap<>();

    /** Buffer a read state update. Overwrites any previous pending value for the same key. */
    public void buffer(String userId, String channelId, String lastReadMessageId) {
        pending.put(userId + ":" + channelId, lastReadMessageId);
    }

    /** Flush all pending updates to DB in a single batch. Runs every 2 seconds. */
    @Scheduled(every = "2s")
    void flush() {
        if (pending.isEmpty()) return;

        // Snapshot and clear atomically per entry
        var snapshot = new ConcurrentHashMap<>(pending);
        for (var key : snapshot.keySet()) {
            pending.remove(key, snapshot.get(key)); // only remove if value hasn't changed
        }

        if (snapshot.isEmpty()) return;

        // Flush in chunks to avoid holding the write lock too long
        var entries = new java.util.ArrayList<>(snapshot.entrySet());
        int chunkSize = 25;
        for (int i = 0; i < entries.size(); i += chunkSize) {
            var chunk = entries.subList(i, Math.min(i + chunkSize, entries.size()));
            try {
                db.transactionVoid(tx -> {
                    for (var entry : chunk) {
                        var parts = entry.getKey().split(":", 2);
                        tx.execute("""
                                INSERT OR REPLACE INTO channel_read_state
                                    (user_id, channel_id, last_read_message_id, mention_count, updated_at)
                                VALUES (?, ?, ?, 0, datetime('now'))
                                """,
                                parts[0], parts[1], entry.getValue());
                    }
                });
            } catch (Exception e) {
                LOG.warnf("Failed to flush read state chunk: %s", e.getMessage());
            }
        }
        LOG.debugf("Flushed %d read state updates", snapshot.size());
    }
}
