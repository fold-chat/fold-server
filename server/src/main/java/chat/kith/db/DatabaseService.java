package chat.kith.db;

import chat.kith.config.KithConfig;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import uk.co.rstl.libsql.LibSql;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

@ApplicationScoped
public class DatabaseService {

    private static final Logger LOG = Logger.getLogger(DatabaseService.class);

    @Inject
    KithConfig config;

    private Arena dbArena;
    private MemorySegment database;

    // Simple thread-local connection pool (libsql connections are cheap)
    private final ThreadLocal<MemorySegment> connectionPool = new ThreadLocal<>();

    @PostConstruct
    void init() {
        LibSql.setup();
        dbArena = Arena.ofShared();
        database = LibSql.databaseInit(
                dbArena,
                config.path(),
                config.url().orElse(null),
                config.authToken().orElse(null),
                config.syncInterval()
        );

        // Verify connectivity
        var conn = getConnection();
        try (var arena = Arena.ofConfined()) {
            var stmt = LibSql.connectionPrepare(arena, conn, "SELECT 1");
            var rows = LibSql.statementQuery(stmt);
            LibSql.rowsDeinit(rows);
            LibSql.statementDeinit(stmt);
        }
        LOG.info("[BOOT] Database ... OK");
    }

    @PreDestroy
    void shutdown() {
        // Connection cleanup happens via thread-local; database is closed here
        if (database != null) {
            LibSql.databaseDeinit(database);
        }
        if (dbArena != null) {
            dbArena.close();
        }
    }

    public MemorySegment getConnection() {
        var conn = connectionPool.get();
        if (conn == null) {
            conn = LibSql.databaseConnect(database);
            connectionPool.set(conn);
        }
        return conn;
    }

    /** Execute a write statement, return rows changed */
    public long execute(String sql, Object... params) {
        try (var arena = Arena.ofConfined()) {
            var conn = getConnection();
            var stmt = LibSql.connectionPrepare(arena, conn, sql);
            bindParams(arena, stmt, params);
            long changed = LibSql.statementExecute(stmt);
            LibSql.statementDeinit(stmt);
            return changed;
        }
    }

    /** Execute a batch of SQL statements (semicolon-separated) */
    public void batch(String sql) {
        try (var arena = Arena.ofConfined()) {
            LibSql.connectionBatch(arena, getConnection(), sql);
        }
    }

    /** Query returning list of row maps */
    public List<Map<String, Object>> query(String sql, Object... params) {
        try (var arena = Arena.ofConfined()) {
            var conn = getConnection();
            var stmt = LibSql.connectionPrepare(arena, conn, sql);
            bindParams(arena, stmt, params);
            var rows = LibSql.statementQuery(stmt);
            var result = readAllRows(rows);
            LibSql.rowsDeinit(rows);
            LibSql.statementDeinit(stmt);
            return result;
        }
    }

    /** Run work in a transaction, auto-commit on success, rollback on failure */
    public <T> T transaction(Function<TxContext, T> work) {
        var conn = getConnection();
        var tx = LibSql.connectionTransaction(conn);
        try {
            T result = work.apply(new TxContext(tx));
            LibSql.transactionCommit(tx);
            return result;
        } catch (Exception e) {
            LibSql.transactionRollback(tx);
            throw e;
        }
    }

    /** Void transaction variant */
    public void transactionVoid(Consumer<TxContext> work) {
        transaction(tx -> { work.accept(tx); return null; });
    }

    public void sync() {
        LibSql.databaseSync(database);
    }

    // --- Transaction context ---

    public static class TxContext {
        private final MemorySegment tx;

        TxContext(MemorySegment tx) {
            this.tx = tx;
        }

        public long execute(String sql, Object... params) {
            try (var arena = Arena.ofConfined()) {
                var stmt = LibSql.transactionPrepare(arena, tx, sql);
                DatabaseService.bindParams(arena, stmt, params);
                long changed = LibSql.statementExecute(stmt);
                LibSql.statementDeinit(stmt);
                return changed;
            }
        }

        public void batch(String sql) {
            try (var arena = Arena.ofConfined()) {
                LibSql.transactionBatch(arena, tx, sql);
            }
        }

        public List<Map<String, Object>> query(String sql, Object... params) {
            try (var arena = Arena.ofConfined()) {
                var stmt = LibSql.transactionPrepare(arena, tx, sql);
                DatabaseService.bindParams(arena, stmt, params);
                var rows = LibSql.statementQuery(stmt);
                var result = DatabaseService.readAllRows(rows);
                LibSql.rowsDeinit(rows);
                LibSql.statementDeinit(stmt);
                return result;
            }
        }
    }

    // --- Internal helpers ---

    private static void bindParams(Arena arena, MemorySegment stmt, Object[] params) {
        for (var param : params) {
            MemorySegment value = switch (param) {
                case null -> LibSql.nullValue();
                case Long l -> LibSql.integer(l);
                case Integer i -> LibSql.integer(i.longValue());
                case Double d -> LibSql.real(d);
                case Float f -> LibSql.real(f.doubleValue());
                case String s -> LibSql.text(arena, s);
                case byte[] b -> LibSql.blob(arena, b);
                default -> throw new IllegalArgumentException("Unsupported param type: " + param.getClass());
            };
            LibSql.bindValue(stmt, value);
        }
    }

    private static List<Map<String, Object>> readAllRows(MemorySegment rows) {
        int colCount = LibSql.rowsColumnCount(rows);
        String[] colNames = new String[colCount];
        for (int i = 0; i < colCount; i++) {
            colNames[i] = LibSql.rowsColumnName(rows, i);
        }

        var result = new ArrayList<Map<String, Object>>();
        while (true) {
            var row = LibSql.rowsNext(rows);
            if (LibSql.rowEmpty(row)) break;
            var map = new LinkedHashMap<String, Object>();
            for (int i = 0; i < colCount; i++) {
                var rv = LibSql.rowValue(row, i);
                map.put(colNames[i], LibSql.extractValue(rv));
            }
            LibSql.rowDeinit(row);
            result.add(map);
        }
        return result;
    }
}
