package chat.fold.service;

import chat.fold.db.DatabaseService;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Buffers reaction inserts and flushes to DB periodically in batched transactions.
 * Reduces write lock contention from individual INSERT per reaction.
 */
@ApplicationScoped
public class ReactionBuffer {

    private static final Logger LOG = Logger.getLogger(ReactionBuffer.class);

    @Inject DatabaseService db;

    private record PendingReaction(String messageId, String userId, String emoji) {}

    private final ConcurrentLinkedQueue<PendingReaction> pending = new ConcurrentLinkedQueue<>();

    /** Queue a reaction for batched insert. */
    public void buffer(String messageId, String userId, String emoji) {
        pending.add(new PendingReaction(messageId, userId, emoji));
    }

    /** Flush pending reactions to DB. Runs every 2 seconds. */
    @Scheduled(every = "2s")
    void flush() {
        if (pending.isEmpty()) return;

        // Drain the queue
        var batch = new java.util.ArrayList<PendingReaction>();
        PendingReaction r;
        while ((r = pending.poll()) != null) {
            batch.add(r);
        }

        if (batch.isEmpty()) return;

        // Flush in chunks of 25
        int chunkSize = 25;
        for (int i = 0; i < batch.size(); i += chunkSize) {
            var chunk = batch.subList(i, Math.min(i + chunkSize, batch.size()));
            try {
                db.transactionVoid(tx -> {
                    for (var reaction : chunk) {
                        tx.execute(
                                "INSERT OR IGNORE INTO reaction (id, message_id, user_id, emoji) VALUES (?, ?, ?, ?)",
                                UUID.randomUUID().toString(), reaction.messageId(), reaction.userId(), reaction.emoji());
                    }
                });
            } catch (Exception e) {
                LOG.warnf("Failed to flush reaction chunk: %s", e.getMessage());
            }
        }
        LOG.debugf("Flushed %d reactions", batch.size());
    }
}
