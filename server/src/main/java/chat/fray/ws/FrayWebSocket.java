package chat.fray.ws;

import chat.fray.auth.JwtService;
import chat.fray.config.FrayMediaConfig;
import chat.fray.db.*;
import chat.fray.event.*;
import chat.fray.security.PermissionService;
import chat.fray.service.LiveKitService;
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
    @Inject ThreadRepository threadRepo;
    @Inject RoleRepository roleRepo;
    @Inject PermissionService permissionService;
    @Inject RoleService roleService;
    @Inject FrayMediaConfig mediaConfig;
    @Inject DatabaseService db;
    @Inject VoiceStateRepository voiceStateRepo;
    @Inject LiveKitService liveKitService;

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
var members = userRepo.listMembers(false);
            var readStates = readStateRepo.findAllForUser(userId);
            var unreadCounts = readStateRepo.unreadCounts(userId);

            // Filter channels by VIEW_CHANNEL permission
            var allChannelIds = allChannels.stream()
                    .map(c -> (String) c.get("id"))
                    .collect(java.util.stream.Collectors.toSet());
            var viewableIds = permissionService.filterViewableChannels(userId, allChannelIds);
            var channels = allChannels.stream()
                    .filter(c -> viewableIds.contains(c.get("id")))
                    .toList();

            // Users with MANAGE_CHANNELS see all categories; others only see populated ones
            List<Map<String, Object>> filteredCategories;
            if (permissionService.hasServerPermission(userId, chat.fray.security.Permission.MANAGE_CHANNELS)) {
                filteredCategories = categories;
            } else {
                var usedCategoryIds = channels.stream()
                        .map(c -> (String) c.get("category_id"))
                        .filter(Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet());
                filteredCategories = categories.stream()
                        .filter(cat -> usedCategoryIds.contains(cat.get("id")))
                        .toList();
            }

            // Serialize roles with permission names
            var roles = roleRepo.findAll().stream()
                    .map(roleService::serializeRole)
                    .toList();

            // Compute user permissions
            var userPermissions = permissionService.computeUserPermissions(userId, viewableIds);

            // Filter unread counts to viewable channels only
            var filteredUnreadCounts = unreadCounts.stream()
                    .filter(uc -> viewableIds.contains(uc.get("channel_id")))
                    .toList();

            // Build thread read states — capped at 10 per channel, only unread threads
            var threadReadStates = new ArrayList<Map<String, Object>>();
            for (var ch : channels) {
                var chId = (String) ch.get("id");
                var unreadThreads = readStateRepo.unreadThreadsForUserChannel(userId, chId, 10);
                threadReadStates.addAll(unreadThreads);
            }

            var hello = new LinkedHashMap<String, Object>();
            hello.put("user", sanitizeUser(user));
            hello.put("channels", channels);
            hello.put("categories", filteredCategories);
            hello.put("members", members);
            hello.put("roles", roles);
            hello.put("read_states", readStates);
            hello.put("unread_counts", filteredUnreadCounts);
            hello.put("thread_read_states", threadReadStates);
            hello.put("user_permissions", userPermissions);
            hello.put("heartbeat_interval_ms", 30000);
            hello.put("session_id", UUID.randomUUID().toString());
            hello.put("media_search", mediaConfig.klipyApiKey().filter(s -> !s.isBlank()).isPresent());

            // Voice states for viewable voice channels
            var voiceChannelIds = channels.stream()
                    .filter(c -> "VOICE".equals(c.get("type")))
                    .map(c -> (String) c.get("id"))
                    .toList();
            var allVoiceStates = new LinkedHashMap<String, Object>();
            for (var vcId : voiceChannelIds) {
                allVoiceStates.put(vcId, voiceStateRepo.findByChannel(vcId));
            }
            hello.put("voice_states", allVoiceStates);

            // Capabilities
            var capabilities = new LinkedHashMap<String, Object>();
            capabilities.put("voice_video", liveKitService.isEnabled());
            hello.put("capabilities", capabilities);

            // Server settings
            var settingsRows = db.query("SELECT key, value FROM server_config WHERE key IN ('server_name', 'server_icon', 'server_description')");
            var serverSettings = new LinkedHashMap<String, Object>();
            for (var row : settingsRows) {
                serverSettings.put((String) row.get("key"), row.get("value"));
            }
            hello.put("server_settings", serverSettings);

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
