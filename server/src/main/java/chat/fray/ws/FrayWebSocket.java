package chat.fray.ws;

import chat.fray.auth.JwtService;
import chat.fray.db.*;
import chat.fray.event.*;
import chat.fray.security.PermissionService;
import chat.fray.service.RoleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.HttpCookie;
import java.util.*;

@WebSocket(path = "/api/ws")
public class FrayWebSocket {

    private static final Logger LOG = Logger.getLogger(FrayWebSocket.class);
    private static final long HEARTBEAT_TIMEOUT_MS = 45_000;

    @Inject JwtService jwtService;
    @Inject SessionRegistry registry;
    @Inject EventBus eventBus;
    @Inject ChannelRepository channelRepo;
    @Inject CategoryRepository categoryRepo;
    @Inject UserRepository userRepo;
    @Inject ReadStateRepository readStateRepo;
    @Inject RoleRepository roleRepo;
    @Inject PermissionService permissionService;
    @Inject RoleService roleService;

    private final ObjectMapper mapper = new ObjectMapper();

    @OnOpen
    public String onOpen(WebSocketConnection connection) {
        System.out.println("opened");
        // Parse cookie from handshake headers
        var cookieHeader = connection.handshakeRequest().header("Cookie");
        String token = parseCookie(cookieHeader, "fray_access");

        if (token == null) {
            LOG.debug("WS connect rejected: no fray_access cookie");
            connection.close().subscribe().with(v -> {}, e -> {});
            return null;
        }

        var claims = jwtService.verify(token);
        if (claims.isEmpty()) {
            LOG.debug("WS connect rejected: invalid token");
            connection.close().subscribe().with(v -> {}, e -> {});
            return null;
        }

        var c = claims.get();
        String userId = c.getSubject();
        String username = (String) c.get("usr");

        registry.register(userId, username, connection);
        LOG.debugf("WS connected: %s (%s)", username, connection.id());

        // Build HELLO payload
        return buildHello(userId);
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        try {
            @SuppressWarnings("unchecked")
            var msg = mapper.readValue(message, Map.class);
            String op = (String) msg.get("op");
            if (op == null) return;

            switch (op) {
                case "HEARTBEAT" -> handleHeartbeat(connection);
                case "TYPING" -> handleTyping(connection, msg);
                case "TYPING_STOP" -> handleTypingStop(connection, msg);
                default -> LOG.debugf("Unknown WS op: %s", op);
            }
        } catch (Exception e) {
            LOG.debugf("Failed to parse WS message: %s", e.getMessage());
        }
    }

    @OnClose
    public void onClose(WebSocketConnection connection) {
        var meta = registry.getMeta(connection);
        registry.unregister(connection);
        if (meta != null) {
            LOG.debugf("WS disconnected: %s (%s)", meta.username(), connection.id());
        }
    }

    private void handleHeartbeat(WebSocketConnection connection) {
        try {
            connection.sendText("{\"op\":\"HEARTBEAT_ACK\"}").subscribe().with(v -> {}, e -> {});
        } catch (Exception e) {
            LOG.debugf("Failed to send heartbeat ack: %s", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void handleTyping(WebSocketConnection connection, Map<String, Object> msg) {
        var userId = registry.getUserId(connection);
        if (userId == null) return;

        var data = (Map<String, Object>) msg.get("d");
        if (data == null) return;
        String channelId = (String) data.get("channel_id");
        if (channelId == null) return;

        var meta = registry.getMeta(connection);
        eventBus.publish(Event.of(
                EventType.TYPING_START,
                Map.of("channel_id", channelId, "user_id", userId, "username", meta != null ? meta.username() : ""),
                Scope.channel(channelId),
                userId
        ));
    }

    @SuppressWarnings("unchecked")
    private void handleTypingStop(WebSocketConnection connection, Map<String, Object> msg) {
        var userId = registry.getUserId(connection);
        if (userId == null) return;

        var data = (Map<String, Object>) msg.get("d");
        if (data == null) return;
        String channelId = (String) data.get("channel_id");
        if (channelId == null) return;

        var meta = registry.getMeta(connection);
        eventBus.publish(Event.of(
                EventType.TYPING_STOP,
                Map.of("channel_id", channelId, "user_id", userId, "username", meta != null ? meta.username() : ""),
                Scope.channel(channelId),
                userId
        ));
    }

    private String buildHello(String userId) {
        try {
            var user = userRepo.findById(userId).orElse(Map.of());
            var allChannels = channelRepo.listAll();
            var categories = categoryRepo.listAll();
            var members = userRepo.listMembers();
            var readStates = readStateRepo.findAllForUser(userId);

            // Filter channels by VIEW_CHANNEL permission
            var allChannelIds = allChannels.stream()
                    .map(c -> (String) c.get("id"))
                    .collect(java.util.stream.Collectors.toSet());
            var viewableIds = permissionService.filterViewableChannels(userId, allChannelIds);
            var channels = allChannels.stream()
                    .filter(c -> viewableIds.contains(c.get("id")))
                    .toList();

            // Serialize roles with permission names
            var roles = roleRepo.findAll().stream()
                    .map(roleService::serializeRole)
                    .toList();

            // Compute user permissions
            var userPermissions = permissionService.computeUserPermissions(userId, viewableIds);

            var hello = new LinkedHashMap<String, Object>();
            hello.put("user", sanitizeUser(user));
            hello.put("channels", channels);
            hello.put("categories", categories);
            hello.put("members", members);
            hello.put("roles", roles);
            hello.put("read_states", readStates);
            hello.put("user_permissions", userPermissions);
            hello.put("heartbeat_interval_ms", 30000);
            hello.put("session_id", UUID.randomUUID().toString());

            return mapper.writeValueAsString(Map.of("op", "HELLO", "d", hello));
        } catch (Exception e) {
            LOG.errorf("Failed to build HELLO: %s", e.getMessage());
            return "{\"op\":\"HELLO\",\"d\":{}}";
        }
    }

    private Map<String, Object> sanitizeUser(Map<String, Object> user) {
        var safe = new LinkedHashMap<String, Object>();
        safe.put("id", user.get("id"));
        safe.put("username", user.get("username"));
        safe.put("display_name", user.get("display_name"));
        safe.put("avatar_url", user.get("avatar_url"));
        safe.put("status_preference", user.get("status_preference"));
        safe.put("status_text", user.get("status_text"));
        safe.put("bio", user.get("bio"));
        return safe;
    }

    private static String parseCookie(String cookieHeader, String name) {
        if (cookieHeader == null || cookieHeader.isBlank()) return null;
        // Cookie header format: "name1=value1; name2=value2"
        for (var part : cookieHeader.split(";")) {
            var trimmed = part.trim();
            if (trimmed.startsWith(name + "=")) {
                return trimmed.substring(name.length() + 1);
            }
        }
        return null;
    }
}
