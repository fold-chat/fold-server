package chat.fold.ws;

import chat.fold.auth.JwtService;
import chat.fold.config.FoldMediaConfig;
import chat.fold.config.RuntimeConfigService;
import chat.fold.service.BackupService;
import chat.fold.service.MediaProcessingService;
import chat.fold.db.*;
import chat.fold.event.*;
import chat.fold.security.PermissionService;
import chat.fold.service.LiveKitService;
import chat.fold.service.MaintenanceService;
import chat.fold.service.RoleService;
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

    private final ObjectMapper mapper = new ObjectMapper();

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

        if (token == null) {
            rejectAuth(connection, "no fold_access cookie");
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
        if (token == null) {
            rejectAuth(connection, "no fold_access cookie");
            return;
        }
        var claims = jwtService.verify(token);
        if (claims.isEmpty()) {
            rejectAuth(connection, "invalid token");
            return;
        }

        var c = claims.get();
        String userId = c.getSubject();
        String username = (String) c.get("usr");

        String sessionId = UUID.randomUUID().toString();
        boolean wasOnline = registry.isOnline(userId);
        registry.register(userId, username, sessionId, connection);
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
        if (token == null) {
            sendResumeError(connection, "no fold_access cookie");
            return;
        }
        var claims = jwtService.verify(token);
        if (claims.isEmpty()) {
            sendResumeError(connection, "invalid token");
            return;
        }

        String userId = claims.get().getSubject();

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
        var suspended = registry.resume(reqSessionId, connection);
        if (suspended == null) {
            // Not in suspended state — register fresh with the same session ID
            String username = (String) claims.get().get("usr");
            registry.register(userId, username, reqSessionId, connection);
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
            var user = userRepo.findById(userId).orElse(Map.of());
            var allChannels = channelRepo.listServerChannels();
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
            hello.put("online_user_ids", registry.onlineUserIds());
            hello.put("heartbeat_interval_ms", 30000);
            hello.put("session_id", sessionId);
            hello.put("version", chat.fold.config.BuildInfo.VERSION);
            hello.put("youtube_embed", mediaConfig.youtubeEmbed());

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
            capabilities.put("voice_mode", liveKitService.getMode());
            capabilities.put("e2ee", runtimeConfig.getBoolean("fold.livekit.e2ee", liveKitConfig.e2ee()));
            capabilities.put("media_search", mediaConfig.klipyApiKey().filter(s -> !s.isBlank()).isPresent());
            var mediaProcessing = new LinkedHashMap<String, Object>();
            mediaProcessing.put("ffmpeg_available", mediaProcessingService.isFfmpegAvailable());
            mediaProcessing.put("video_mode", runtimeConfig.getString("fold.media-processing.video-mode",
                    mediaProcessingService.getVideoMode()));
            capabilities.put("media_processing", mediaProcessing);
            hello.put("capabilities", capabilities);

            // Server settings
            var settingsRows = db.query("SELECT key, value FROM server_config WHERE key IN ('server_name', 'server_icon', 'server_description')");
            var serverSettings = new LinkedHashMap<String, Object>();
            for (var row : settingsRows) {
                serverSettings.put((String) row.get("key"), row.get("value"));
            }
            serverSettings.put("maintenance_enabled", maintenanceService.isEnabled());
            serverSettings.put("maintenance_message", maintenanceService.getMessage());
            hello.put("server_settings", serverSettings);

            // DM conversations
            var dmConversations = dmRepo.findConversationsForUser(userId);
            var dmConvList = new ArrayList<Map<String, Object>>();
            var dmChannelIds = new HashSet<String>();
            for (var conv : dmConversations) {
                var enriched = new LinkedHashMap<String, Object>(conv);
                String dmChId = (String) conv.get("channel_id");
                dmChannelIds.add(dmChId);
                enriched.put("participants", dmRepo.findParticipantDetails(dmChId));
                enriched.put("is_blocked", java.util.Objects.equals(conv.get("is_blocked"), 1L));
                dmConvList.add(enriched);
            }
            hello.put("dm_conversations", dmConvList);
            hello.put("dm_blocked_user_ids", dmBlockRepo.getBlockedIds(userId));

            // DM unread counts (filter from existing unreadCounts)
            var dmUnreadCounts = unreadCounts.stream()
                    .filter(uc -> dmChannelIds.contains(uc.get("channel_id")))
                    .toList();
            hello.put("dm_unread_counts", dmUnreadCounts);

            // Custom emoji
            var customEmoji = emojiRepo.listAll().stream().map(e -> {
                var em = new LinkedHashMap<String, Object>();
                em.put("id", e.get("id"));
                em.put("name", e.get("name"));
                em.put("url", "/api/v0/files/" + e.get("stored_name"));
                em.put("uploader_id", e.get("uploader_id"));
                return (Map<String, Object>) em;
            }).toList();
            hello.put("custom_emoji", customEmoji);

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
