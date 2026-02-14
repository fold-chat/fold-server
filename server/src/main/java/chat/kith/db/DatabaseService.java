package chat.kith.db;

import chat.kith.config.KithConfig;
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

    private Database database;

    // Simple thread-local connection pool (libsql connections are cheap)
    private final ThreadLocal<Connection> connectionPool = new ThreadLocal<>();

    @PostConstruct
    void init() {
        var builder = Database.builder(config.path())
                .journalMode("WAL")
                .busyTimeout(5000)
                .foreignKeys(true);
        config.url().ifPresent(builder::url);
        config.authToken().ifPresent(builder::authToken);
        builder.syncInterval(config.syncInterval());
        database = builder.build();

        // Verify connectivity
        try (var conn = database.connect()) {
            conn.batch("SELECT 1");
        }
        LOG.info("[BOOT] Database ... OK");
    }

    @PreDestroy
    void shutdown() {
        if (database != null) {
            database.close();
        }
    }

    public Connection getConnection() {
        var conn = connectionPool.get();
        if (conn == null) {
            conn = database.connect();
            connectionPool.set(conn);
        }
        return conn;
    }

    /** Execute a write statement, return rows changed */
    public long execute(String sql, Object... params) {
        try (var stmt = getConnection().prepare(sql)) {
            bindParams(stmt, params);
            return stmt.execute();
        }
    }

    /** Execute a batch of SQL statements (semicolon-separated) */
    public void batch(String sql) {
        getConnection().batch(sql);
    }

    /** Query returning list of row maps */
    public List<Map<String, Object>> query(String sql, Object... params) {
        try (var stmt = getConnection().prepare(sql)) {
            bindParams(stmt, params);
            try (var rows = stmt.query()) {
                return readAllRows(rows);
            }
        }
    }

    /** Run work in a transaction, auto-commit on success, auto-rollback on failure */
    public <T> T transaction(Function<TxContext, T> work) {
        try (var tx = getConnection().transaction()) {
            T result = work.apply(new TxContext(tx));
            tx.commit();
            return result;
        }
    }

    /** Void transaction variant */
    public void transactionVoid(Consumer<TxContext> work) {
        transaction(tx -> { work.accept(tx); return null; });
    }

    public void sync() {
        database.sync();
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
