package chat.fold.ws;

import chat.fold.auth.JwtService;
import chat.fold.config.FoldMediaConfig;
import chat.fold.config.RuntimeConfigService;
import chat.fold.service.BackupService;
import chat.fold.service.HelloCacheService;
import chat.fold.service.MediaProcessingService;
import chat.fold.db.*;
import chat.fold.event.*;
import chat.fold.security.PermissionService;
import chat.fold.service.LiveKitService;
import chat.fold.service.MaintenanceService;
import chat.fold.service.RoleService;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.*;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.*;

@WebSocket(path = "/api/ws")
public class FoldWebSocket {

    private static final Logger LOG = Logger.getLogger(FoldWebSocket.class);
    private static final long HEARTBEAT_TIMEOUT_MS = 45_000;

    @Inject JwtService jwtService;
    @Inject chat.fold.db.BotRepository botRepo;
    @Inject SessionRegistry registry;
    @Inject EventBus eventBus;
    @Inject EventBuffer eventBuffer;
    @Inject ChannelRepository channelRepo;
    @Inject CategoryRepository categoryRepo;
    @Inject UserRepository userRepo;
    @Inject ReadStateRepository readStateRepo;
    @Inject ThreadRepository threadRepo;
    @Inject RoleRepository roleRepo;
    @Inject PermissionService permissionService;
    @Inject RoleService roleService;
    @Inject DmRepository dmRepo;
    @Inject DmBlockRepository dmBlockRepo;
    @Inject FoldMediaConfig mediaConfig;
    @Inject DatabaseService db;
    @Inject VoiceStateRepository voiceStateRepo;
    @Inject LiveKitService liveKitService;
    @Inject chat.fold.config.FoldLiveKitConfig liveKitConfig;
    @Inject RuntimeConfigService runtimeConfig;
    @Inject EmojiRepository emojiRepo;
    @Inject MaintenanceService maintenanceService;
    @Inject MediaProcessingService mediaProcessingService;
    @Inject BackupService backupService;
    @Inject HelloCacheService helloCacheService;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectMapper helloMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    @OnOpen
    public void onOpen(WebSocketConnection connection) {
        // Reject if restart required
        if (backupService.isRestartRequired()) {
            try {
                connection.sendText("{\"op\":\"SERVER_RESTART_REQUIRED\",\"d\":{\"message\":\"Backup restored. Please restart the server.\"}}")
                        .subscribe().with(
                                v -> connection.close().subscribe().with(v2 -> {}, e2 -> {}),
                                e -> connection.close().subscribe().with(v2 -> {}, e2 -> {})
                        );
            } catch (Exception e) {
                connection.close().subscribe().with(v -> {}, e2 -> {});
            }
            return;
        }

        // Parse cookie from handshake headers
        var cookieHeader = connection.handshakeRequest().header("Cookie");
        String token = parseCookie(cookieHeader, "fold_access");

        // Bot token auth fallback
        if (token == null) {
            var authHeader = connection.handshakeRequest().header("Authorization");
            if (authHeader != null && authHeader.startsWith("Bot ")) {
                String rawToken = authHeader.substring(4).trim();
                String hash = chat.fold.db.BotRepository.hashToken(rawToken);
                var botUser = botRepo.findByTokenHash(hash);
                if (botUser.isPresent()) {
                    Long botEnabled = (Long) botUser.get().get("bot_enabled");
                    if (botEnabled != null && botEnabled != 0) {
                        LOG.debugf("WS bot authenticated: %s (%s)", botUser.get().get("username"), connection.id());
                        try {
                            connection.sendText("{\"op\":\"READY\"}").subscribe().with(v -> {}, e -> {});
                        } catch (Exception e) {
                            LOG.debugf("Failed to send READY: %s", e.getMessage());
                        }
                        return;
                    }
                }
            }
            rejectAuth(connection, "no auth");
            return;
        }

        var claims = jwtService.verify(token);
        if (claims.isEmpty()) {
            rejectAuth(connection, "invalid token");
            return;
        }

        LOG.debugf("WS authenticated: %s (%s)", claims.get().get("usr"), connection.id());

        // Don't register or send HELLO yet — wait for IDENTIFY or RESUME from client.
        // Send READY to signal the client can proceed.
        try {
            connection.sendText("{\"op\":\"READY\"}").subscribe().with(v -> {}, e -> {});
        } catch (Exception e) {
            LOG.debugf("Failed to send READY: %s", e.getMessage());
        }
    }

    @OnTextMessage
    public void onMessage(WebSocketConnection connection, String message) {
        try {
            @SuppressWarnings("unchecked")
            var msg = mapper.readValue(message, Map.class);
            String op = (String) msg.get("op");
            if (op == null) return;

            switch (op) {
                case "IDENTIFY" -> handleIdentify(connection);
                case "RESUME" -> handleResume(connection, msg);
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
        // Suspend session instead of unregistering — allows RESUME
        registry.suspend(connection);
        if (meta != null) {
            LOG.debugf("WS disconnected (suspended): %s (%s) session=%s", meta.username(), connection.id(), meta.sessionId());
            // If user has no remaining connections, they went offline
            if (!registry.isOnline(meta.userId())) {
                userRepo.updateLastSeen(meta.userId());
                var user = userRepo.findById(meta.userId());
                String lastSeen = user.map(u -> (String) u.get("last_seen_at")).orElse(null);
                var data = new LinkedHashMap<String, Object>();
                data.put("user_id", meta.userId());
                data.put("status", "offline");
                data.put("last_seen_at", lastSeen);
                eventBus.publish(Event.of(EventType.PRESENCE_UPDATE, data, Scope.server()));
            }
        }
    }

    private void handleIdentify(WebSocketConnection connection) {
        var cookieHeader = connection.handshakeRequest().header("Cookie");
        String token = parseCookie(cookieHeader, "fold_access");

        String userId;
        String username;
        boolean isBot = false;

        if (token != null) {
            var claims = jwtService.verify(token);
            if (claims.isEmpty()) {
                rejectAuth(connection, "invalid token");
                return;
            }
            var c = claims.get();
            userId = c.getSubject();
            username = (String) c.get("usr");
        } else {
            // Try bot token
            var authHeader = connection.handshakeRequest().header("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bot ")) {
                rejectAuth(connection, "no auth");
                return;
            }
            String rawToken = authHeader.substring(4).trim();
            String hash = chat.fold.db.BotRepository.hashToken(rawToken);
            var botUser = botRepo.findByTokenHash(hash);
            if (botUser.isEmpty()) {
                rejectAuth(connection, "invalid bot token");
                return;
            }
            Long botEnabled = (Long) botUser.get().get("bot_enabled");
            if (botEnabled == null || botEnabled == 0) {
                rejectAuth(connection, "bot disabled");
                return;
            }
            userId = (String) botUser.get().get("id");
            username = (String) botUser.get().get("username");
            isBot = true;
            botRepo.updateLastUsed(userId);
        }

        String sessionId = UUID.randomUUID().toString();
        boolean wasOnline = registry.isOnline(userId);
        registry.register(userId, username, sessionId, connection, isBot);
        eventBuffer.createBuffer(sessionId, userId);
        LOG.debugf("WS identified: %s (%s) session=%s", username, connection.id(), sessionId);

        // Broadcast presence if user just came online
        if (!wasOnline) {
            eventBus.publish(Event.of(
                    EventType.PRESENCE_UPDATE,
                    Map.of("user_id", userId, "status", "online"),
                    Scope.server()
            ));
        }

        // Send HELLO payload
        String hello = buildHello(userId, sessionId);
        try {
            connection.sendText(hello).subscribe().with(v -> {}, e -> {});
        } catch (Exception e) {
            LOG.debugf("Failed to send HELLO: %s", e.getMessage());
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
    private void handleResume(WebSocketConnection connection, Map<String, Object> msg) {
        var data = (Map<String, Object>) msg.get("d");
        if (data == null) {
            sendResumeError(connection, "missing data");
            return;
        }

        String reqSessionId = (String) data.get("session_id");
        var lastSeqRaw = data.get("last_sequence");
        if (reqSessionId == null || lastSeqRaw == null) {
            sendResumeError(connection, "missing session_id or last_sequence");
            return;
        }

        long lastSequence;
        try {
            lastSequence = lastSeqRaw instanceof Number n ? n.longValue() : Long.parseLong(lastSeqRaw.toString());
        } catch (NumberFormatException e) {
            sendResumeError(connection, "invalid last_sequence");
            return;
        }

        // Authenticate the connection
        var cookieHeader = connection.handshakeRequest().header("Cookie");
        String token = parseCookie(cookieHeader, "fold_access");

        String userId;
        boolean isBot = false;

        if (token != null) {
            var claims = jwtService.verify(token);
            if (claims.isEmpty()) {
                sendResumeError(connection, "invalid token");
                return;
            }
            userId = claims.get().getSubject();
        } else {
            var authHeader = connection.handshakeRequest().header("Authorization");
            if (authHeader == null || !authHeader.startsWith("Bot ")) {
                sendResumeError(connection, "no auth");
                return;
            }
            String rawToken = authHeader.substring(4).trim();
            String hash = chat.fold.db.BotRepository.hashToken(rawToken);
            var botUser = botRepo.findByTokenHash(hash);
            if (botUser.isEmpty()) {
                sendResumeError(connection, "invalid bot token");
                return;
            }
            Long botEnabled = (Long) botUser.get().get("bot_enabled");
            if (botEnabled == null || botEnabled == 0) {
                sendResumeError(connection, "bot disabled");
                return;
            }
            userId = (String) botUser.get().get("id");
            isBot = true;
            botRepo.updateLastUsed(userId);
        }

        // Verify the session belongs to this user
        String bufferUserId = eventBuffer.getUserId(reqSessionId);
        if (bufferUserId == null || !bufferUserId.equals(userId)) {
            sendResumeError(connection, "session not found");
            return;
        }

        // Get missed events
        var missedEvents = eventBuffer.eventsSince(reqSessionId, lastSequence);
        if (missedEvents == null) {
            // Sequence fell off the buffer — client must do full HELLO
            sendResumeError(connection, "sequence too old");
            return;
        }

        // Resume the session
        boolean wasOnline = registry.isOnline(userId);
        var suspended = registry.resume(reqSessionId, connection, isBot);
        if (suspended == null) {
            // Not in suspended state — register fresh with the same session ID
            String username = userRepo.findById(userId).map(u -> (String) u.get("username")).orElse(userId);
            registry.register(userId, username, reqSessionId, connection, isBot);
        }

        LOG.debugf("WS resumed: user=%s session=%s replaying %d events from seq %d",
                userId, reqSessionId, missedEvents.size(), lastSequence);

        // Broadcast presence if user came back online
        if (!wasOnline) {
            eventBus.publish(Event.of(
                    EventType.PRESENCE_UPDATE,
                    Map.of("user_id", userId, "status", "online"),
                    Scope.server()
            ));
        }

        // Send RESUMED ack
        try {
            connection.sendText("{\"op\":\"RESUMED\"}").subscribe().with(v -> {}, e -> {});
        } catch (Exception e) {
            LOG.debugf("Failed to send RESUMED: %s", e.getMessage());
        }

        // Replay missed events
        for (var event : missedEvents) {
            try {
                connection.sendText(event.json()).subscribe().with(v -> {}, e -> {});
            } catch (Exception e) {
                LOG.debugf("Failed to replay event seq=%d: %s", event.sequence(), e.getMessage());
                break;
            }
        }
    }

    private void sendResumeError(WebSocketConnection connection, String reason) {
        LOG.debugf("RESUME failed: %s", reason);
        try {
            connection.sendText("{\"op\":\"RESUME_FAILED\"}").subscribe().with(v -> {}, e -> {});
        } catch (Exception e) {
            LOG.debugf("Failed to send RESUME_FAILED: %s", e.getMessage());
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

        // DM channels: only participants can send typing events
        if (permissionService.isDmChannel(channelId)) {
            if (!permissionService.isDmParticipant(channelId, userId)) return;
        }

        var meta = registry.getMeta(connection);
        var payload = Map.of("channel_id", channelId, "user_id", userId, "username", meta != null ? meta.username() : "");
        Scope scope = permissionService.isDmChannel(channelId)
                ? Scope.users(dmRepo.findParticipants(channelId))
                : Scope.channel(channelId);
        eventBus.publish(Event.of(EventType.TYPING_START, payload, scope, userId));
    }

    @SuppressWarnings("unchecked")
    private void handleTypingStop(WebSocketConnection connection, Map<String, Object> msg) {
        var userId = registry.getUserId(connection);
        if (userId == null) return;

        var data = (Map<String, Object>) msg.get("d");
        if (data == null) return;
        String channelId = (String) data.get("channel_id");
        if (channelId == null) return;

        if (permissionService.isDmChannel(channelId)) {
            if (!permissionService.isDmParticipant(channelId, userId)) return;
        }

        var meta = registry.getMeta(connection);
        var payload = Map.of("channel_id", channelId, "user_id", userId, "username", meta != null ? meta.username() : "");
        Scope scope = permissionService.isDmChannel(channelId)
                ? Scope.users(dmRepo.findParticipants(channelId))
                : Scope.channel(channelId);
        eventBus.publish(Event.of(EventType.TYPING_STOP, payload, scope, userId));
    }

    private String buildHello(String userId, String sessionId) {
        try {
            long helloStart = System.nanoTime();
            long t;

            t = System.nanoTime();
            var user = userRepo.findById(userId).orElse(Map.of());
            long userMs = (System.nanoTime() - t) / 1_000_000;

            // Shared data from cache
            t = System.nanoTime();
            var allChannels = helloCacheService.getChannels();
            var categories = helloCacheService.getCategories();
            var members = helloCacheService.getMembers();
            var roles = helloCacheService.getRoles();
            var customEmoji = helloCacheService.getCustomEmoji();
            var serverSettings = helloCacheService.getServerSettings();
            var capabilities = helloCacheService.getCapabilities();
            long cacheMs = (System.nanoTime() - t) / 1_000_000;

            // Bulk: filter viewable channels + compute permissions in one pass (1 DB query for overrides)
            t = System.nanoTime();
            var allChannelIds = allChannels.stream()
                    .map(c -> (String) c.get("id"))
                    .collect(java.util.stream.Collectors.toSet());
            var permResult = permissionService.filterAndComputePermissionsBulk(userId, allChannelIds);
            var viewableIds = permResult.viewable();
            var userPermissions = permResult.permissions();
            var channels = allChannels.stream()
                    .filter(c -> viewableIds.contains(c.get("id")))
                    .toList();
            long permsMs = (System.nanoTime() - t) / 1_000_000;

            // Users with MANAGE_CHANNELS see all categories; others only see populated ones
            List<Map<String, Object>> filteredCategories;
            if (permissionService.hasServerPermission(userId, chat.fold.security.Permission.MANAGE_CHANNELS)) {
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

            // Per-user data — scoped to viewable channels only
            t = System.nanoTime();
            var readStates = readStateRepo.findAllForUser(userId);
            long readStatesMs = (System.nanoTime() - t) / 1_000_000;

            t = System.nanoTime();
            var unreadCounts = readStateRepo.unreadCountsForChannels(userId, viewableIds);
            long unreadMs = (System.nanoTime() - t) / 1_000_000;

            // Bulk: thread read states — single query across all viewable channels
            t = System.nanoTime();
            var threadReadStates = readStateRepo.unreadThreadsForUserChannels(userId, viewableIds, 10);
            long threadMs = (System.nanoTime() - t) / 1_000_000;

            // Bulk: voice states — single query across all voice channels
            t = System.nanoTime();
            var voiceChannelIds = channels.stream()
                    .filter(c -> "VOICE".equals(c.get("type")))
                    .map(c -> (String) c.get("id"))
                    .toList();
            var voiceRows = voiceStateRepo.findByChannels(voiceChannelIds);
            var allVoiceStates = new LinkedHashMap<String, Object>();
            for (var vcId : voiceChannelIds) allVoiceStates.put(vcId, new ArrayList<>());
            for (var row : voiceRows) {
                var chId = (String) row.get("channel_id");
                @SuppressWarnings("unchecked")
                var list = (List<Map<String, Object>>) allVoiceStates.get(chId);
                if (list != null) list.add(row);
            }
            long voiceMs = (System.nanoTime() - t) / 1_000_000;

            var hello = new LinkedHashMap<String, Object>();
            hello.put("user", sanitizeUser(user));
            hello.put("channels", channels);
            hello.put("categories", filteredCategories);
            hello.put("members", members);
            hello.put("roles", roles);
            hello.put("read_states", readStates);
            hello.put("unread_counts", unreadCounts);
            hello.put("thread_read_states", threadReadStates);
            hello.put("user_permissions", userPermissions);
            hello.put("online_user_ids", registry.onlineUserIds());
            hello.put("heartbeat_interval_ms", 30000);
            hello.put("session_id", sessionId);
            hello.put("version", chat.fold.config.BuildInfo.VERSION);
            hello.put("youtube_embed", mediaConfig.youtubeEmbed());
            hello.put("voice_states", allVoiceStates);
            hello.put("capabilities", capabilities);
            hello.put("server_settings", serverSettings);
            hello.put("custom_emoji", customEmoji);

            t = System.nanoTime();
            var json = helloMapper.writeValueAsString(Map.of("op", "HELLO", "d", hello));
            long serializeMs = (System.nanoTime() - t) / 1_000_000;

            long totalMs = (System.nanoTime() - helloStart) / 1_000_000;
            LOG.infof("HELLO [%s] total=%dms | user=%d cache=%d perms=%d readStates=%d unread=%d threads=%d voice=%d serialize=%dms (%d bytes)",
                    userId, totalMs, userMs, cacheMs, permsMs, readStatesMs, unreadMs, threadMs, voiceMs, serializeMs, json.length());

            return json;
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

    private void rejectAuth(WebSocketConnection connection, String reason) {
        LOG.debug("WS connect rejected: " + reason);
        connection.sendText("{\"op\":\"AUTH_FAILED\"}").subscribe().with(
            v -> connection.close().subscribe().with(v2 -> {}, e2 -> {}),
            e -> connection.close().subscribe().with(v2 -> {}, e2 -> {})
        );
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
