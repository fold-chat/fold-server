package chat.fray.event;

import java.util.Set;

public sealed interface Scope {

    record Channel(String channelId) implements Scope {}
    record Server() implements Scope {}
    record User(String userId) implements Scope {}
    record Users(Set<String> userIds) implements Scope {}

    static Scope channel(String channelId) { return new Channel(channelId); }
    static Scope server() { return new Server(); }
    static Scope user(String userId) { return new User(userId); }
    static Scope users(Set<String> userIds) { return new Users(userIds); }
}
