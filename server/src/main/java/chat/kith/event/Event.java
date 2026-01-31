package chat.kith.event;

public record Event(EventType type, Object data, Scope scope, String excludeUserId) {

    public static Event of(EventType type, Object data, Scope scope) {
        return new Event(type, data, scope, null);
    }

    public static Event of(EventType type, Object data, Scope scope, String excludeUserId) {
        return new Event(type, data, scope, excludeUserId);
    }
}
