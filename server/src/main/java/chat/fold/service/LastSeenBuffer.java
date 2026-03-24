package chat.fold.service;

import chat.fold.db.DatabaseService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Buffers last_seen_at updates in memory and flushes to DB periodically.
 * Multiple updates for the same userId are coalesced — only the latest matters.
 * Prevents mass disconnect storms from flooding the DB with 250 individual writes.
 */
@ApplicationScoped
public class LastSeenBuffer {

    private static final Logger LOG = Logger.getLogger(LastSeenBuffer.class);

    @Inject DatabaseService db;

    // userId → true (presence = needs update; timestamp generated at flush time)
    private final ConcurrentHashMap<String, Boolean> pending = new ConcurrentHashMap<>();

    /** Mark a user's last_seen_at as needing update. */
    public void buffer(String userId) {
        pending.put(userId, Boolean.TRUE);
    }

    /** Flush all pending updates. Runs every 5 seconds. */
    @Scheduled(every = "5s")
    void flush() {
        if (pending.isEmpty()) return;

        var userIds = new java.util.ArrayList<>(pending.keySet());
        pending.keySet().removeAll(userIds);

        if (userIds.isEmpty()) return;

        // Flush in chunks
        int chunkSize = 25;
        for (int i = 0; i < userIds.size(); i += chunkSize) {
            var chunk = userIds.subList(i, Math.min(i + chunkSize, userIds.size()));
            try {
                db.transactionVoid(tx -> {
                    for (var userId : chunk) {
                        tx.execute("UPDATE user SET last_seen_at = datetime('now') WHERE id = ?", userId);
                    }
                });
            } catch (Exception e) {
                LOG.warnf("Failed to flush last_seen chunk: %s", e.getMessage());
            }
        }
        LOG.debugf("Flushed %d last_seen updates", userIds.size());
    }
}
