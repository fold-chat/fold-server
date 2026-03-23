package chat.fold.db;

import chat.fold.config.FoldConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import uk.co.rstl.libsql.Connection;
import uk.co.rstl.libsql.Database;
import uk.co.rstl.libsql.Rows;
import uk.co.rstl.libsql.Statement;
import uk.co.rstl.libsql.Transaction;

import io.quarkus.scheduler.Scheduled;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

@ApplicationScoped
public class DatabaseService {

    private static final Logger LOG = Logger.getLogger(DatabaseService.class);

    @Inject
    FoldConfig config;

    private Database database;
    private BlockingQueue<Connection> pool;
    private int poolSize;

    // --- Metrics ---
    private final AtomicLong queryCount = new AtomicLong();
    private final AtomicLong writeCount = new AtomicLong();
    private final AtomicLong totalQueryMs = new AtomicLong();
    private final AtomicLong totalWriteMs = new AtomicLong();
    private final AtomicLong totalPoolWaitMs = new AtomicLong();
    private final AtomicLong poolTimeouts = new AtomicLong();
    private final ConcurrentHashMap<String, AtomicLong> slowQueries = new ConcurrentHashMap<>();
    private static final long SLOW_THRESHOLD_MS = 50;

    @PostConstruct
    void init() {
        // Ensure parent directory exists (e.g. /persist/pr-N/ for preview deploys)
        var dbParent = Path.of(config.path()).getParent();
        if (dbParent != null) {
            try {
                Files.createDirectories(dbParent);
            } catch (IOException e) {
                throw new RuntimeException("Failed to create database directory: " + dbParent, e);
            }
        }

        var builder = Database.builder(config.path())
                .journalMode("WAL")
                .busyTimeout(5000)
                .foreignKeys(true);
        config.url().ifPresent(builder::url);
        config.authToken().ifPresent(builder::authToken);
        builder.syncInterval(config.syncInterval());
        database = builder.build();

        // Initialize connection pool — PRAGMAs run once per connection at creation
        poolSize = Math.max(1, config.poolSize());
        pool = new ArrayBlockingQueue<>(poolSize);
        for (int i = 0; i < poolSize; i++) {
            pool.add(database.connect());
        }
        LOG.infof("[BOOT] Database ... OK (pool=%d)", poolSize);
    }

    @PreDestroy
    public void shutdown() {
        if (pool != null) {
            Connection conn;
            while ((conn = pool.poll()) != null) {
                conn.close();
            }
        }
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private Connection borrowConnection() {
        long start = System.nanoTime();
        try {
            var conn = pool.poll(10, TimeUnit.SECONDS);
            long waitMs = (System.nanoTime() - start) / 1_000_000;
            totalPoolWaitMs.addAndGet(waitMs);
            if (conn == null) {
                poolTimeouts.incrementAndGet();
                throw new RuntimeException("Connection pool exhausted (pool=" + poolSize + ", timeout=10s)");
            }
            if (waitMs > 100) {
                LOG.warnf("DB pool wait: %dms (available=%d/%d)", waitMs, pool.size(), poolSize);
            }
            return conn;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for connection", e);
        }
    }

    private void returnConnection(Connection conn) {
        if (conn != null && !pool.offer(conn)) {
            // Pool is full (shouldn't happen), close the excess connection
            conn.close();
        }
    }

    /** Execute a write statement, return rows changed */
    public long execute(String sql, Object... params) {
        var conn = borrowConnection();
        long start = System.nanoTime();
        try (var stmt = conn.prepare(sql)) {
            bindParams(stmt, params);
            return stmt.execute();
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            writeCount.incrementAndGet();
            totalWriteMs.addAndGet(ms);
            if (ms > SLOW_THRESHOLD_MS) trackSlow(sql, ms);
            returnConnection(conn);
        }
    }

    /** Execute a batch of SQL statements (semicolon-separated) */
    public void batch(String sql) {
        var conn = borrowConnection();
        try {
            conn.batch(sql);
        } finally {
            returnConnection(conn);
        }
    }

    /** Query returning list of row maps */
    public List<Map<String, Object>> query(String sql, Object... params) {
        var conn = borrowConnection();
        long start = System.nanoTime();
        try (var stmt = conn.prepare(sql)) {
            bindParams(stmt, params);
            try (var rows = stmt.query()) {
                return readAllRows(rows);
            }
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            queryCount.incrementAndGet();
            totalQueryMs.addAndGet(ms);
            if (ms > SLOW_THRESHOLD_MS) trackSlow(sql, ms);
            returnConnection(conn);
        }
    }

    /** Run work in a transaction, auto-commit on success, auto-rollback on failure */
    public <T> T transaction(Function<TxContext, T> work) {
        var conn = borrowConnection();
        try {
            var tx = conn.transaction();
            try {
                T result = work.apply(new TxContext(tx));
                tx.commit();
                return result;
            } catch (Exception e) {
                // Transaction auto-rolls back on close if not committed
                tx.close();
                throw e;
            }
        } finally {
            returnConnection(conn);
        }
    }

    /** Void transaction variant */
    public void transactionVoid(Consumer<TxContext> work) {
        transaction(tx -> { work.accept(tx); return null; });
    }

    public void sync() {
        database.sync();
    }

    // --- Metrics ---

    private void trackSlow(String sql, long ms) {
        // Use first 80 chars of SQL as key
        var key = sql.strip().substring(0, Math.min(80, sql.strip().length()));
        slowQueries.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(ms);
    }

    @Scheduled(every = "10s")
    void logMetrics() {
        long reads = queryCount.getAndSet(0);
        long writes = writeCount.getAndSet(0);
        long readMs = totalQueryMs.getAndSet(0);
        long writeMs = totalWriteMs.getAndSet(0);
        long waitMs = totalPoolWaitMs.getAndSet(0);
        long timeouts = poolTimeouts.getAndSet(0);

        if (reads + writes == 0) return;

        long avgRead = reads > 0 ? readMs / reads : 0;
        long avgWrite = writes > 0 ? writeMs / writes : 0;

        LOG.infof("DB [10s] reads=%d (avg %dms, total %dms) writes=%d (avg %dms, total %dms) poolWait=%dms timeouts=%d avail=%d/%d",
                reads, avgRead, readMs, writes, avgWrite, writeMs, waitMs, timeouts, pool.size(), poolSize);

        // Log top slow queries and reset
        if (!slowQueries.isEmpty()) {
            var sorted = slowQueries.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                    .limit(5)
                    .toList();
            for (var entry : sorted) {
                LOG.infof("  SLOW: %dms total — %s", entry.getValue().get(), entry.getKey());
            }
            slowQueries.clear();
        }
    }

    // --- Transaction context ---

    public static class TxContext {
        private final Transaction tx;

        TxContext(Transaction tx) {
            this.tx = tx;
        }

        public long execute(String sql, Object... params) {
            try (var stmt = tx.prepare(sql)) {
                DatabaseService.bindParams(stmt, params);
                return stmt.execute();
            }
        }

        public void batch(String sql) {
            tx.batch(sql);
        }

        public List<Map<String, Object>> query(String sql, Object... params) {
            try (var stmt = tx.prepare(sql)) {
                DatabaseService.bindParams(stmt, params);
                try (var rows = stmt.query()) {
                    return DatabaseService.readAllRows(rows);
                }
            }
        }
    }

    // --- Internal helpers ---

    private static void bindParams(Statement stmt, Object[] params) {
        for (var param : params) {
            switch (param) {
                case null -> stmt.bindNull();
                case Long l -> stmt.bind(l);
                case Integer i -> stmt.bind(i.longValue());
                case Double d -> stmt.bind(d);
                case Float f -> stmt.bind(f.doubleValue());
                case String s -> stmt.bind(s);
                case byte[] b -> stmt.bind(b);
                default -> throw new IllegalArgumentException("Unsupported param type: " + param.getClass());
            };
        }
    }

    private static List<Map<String, Object>> readAllRows(Rows rows) {
        int colCount = rows.columnCount();
        String[] colNames = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            colNames[i] = rows.columnName(i);
        }

        var result = new ArrayList<Map<String, Object>>();
        for (var row : rows) {
            var map = new LinkedHashMap<String, Object>();
            for (int i = 0; i < colCount; i++) {
                map.put(colNames[i], row.get(i));
            }
            result.add(map);
            row.close();
        }
        return result;
    }
}
