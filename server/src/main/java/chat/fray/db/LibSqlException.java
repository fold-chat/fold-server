package chat.fray.db;

public class LibSqlException extends RuntimeException {
    public LibSqlException(String message) {
        super(message);
    }

    public LibSqlException(String message, Throwable cause) {
        super(message, cause);
    }
}
