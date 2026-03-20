package chat.fold.cli;

import uk.co.rstl.libsql.Connection;
import uk.co.rstl.libsql.Database;
import uk.co.rstl.libsql.Rows;
import uk.co.rstl.libsql.Statement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lightweight DB access for admin CLI — bypasses CDI / DatabaseService. */
public class AdminDbHelper implements AutoCloseable {

    private final Database database;
    private final Connection connection;

    public AdminDbHelper(String dbPath) {
        var builder = Database.builder(dbPath)
                .journalMode("WAL")
                .busyTimeout(5000)
                .foreignKeys(true);

        var url = System.getenv("FOLD_DB_URL");
        var authToken = System.getenv("FOLD_DB_AUTH_TOKEN");
        if (url != null && !url.isBlank()) builder.url(url);
        if (authToken != null && !authToken.isBlank()) builder.authToken(authToken);

        this.database = builder.build();
        this.connection = database.connect();
    }

    public long execute(String sql, Object... params) {
        try (var stmt = connection.prepare(sql)) {
            bindParams(stmt, params);
            return stmt.execute();
        }
    }

    public List<Map<String, Object>> query(String sql, Object... params) {
        try (var stmt = connection.prepare(sql)) {
            bindParams(stmt, params);
            try (var rows = stmt.query()) {
                return readAllRows(rows);
            }
        }
    }

    @Override
    public void close() {
        connection.close();
        database.close();
    }

    private static void bindParams(Statement stmt, Object[] params) {
        for (var param : params) {
            switch (param) {
                case null -> stmt.bindNull();
                case Long l -> stmt.bind(l);
                case Integer i -> stmt.bind(i.longValue());
                case String s -> stmt.bind(s);
                default -> throw new IllegalArgumentException("Unsupported param type: " + param.getClass());
            }
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
