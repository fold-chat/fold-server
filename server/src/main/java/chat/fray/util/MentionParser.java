package chat.fray.util;

import chat.fray.db.RoleRepository;
import chat.fray.db.UserRepository;
import chat.fray.security.Permission;
import chat.fray.security.PermissionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;
import java.util.regex.Pattern;

@ApplicationScoped
public class MentionParser {

    private static final Pattern USER_MENTION_PATTERN = Pattern.compile("<@([a-f0-9-]{36})>");
    private static final Pattern ROLE_MENTION_PATTERN = Pattern.compile("<@&([a-zA-Z0-9_-]+)>");
    private static final Pattern EVERYONE_PATTERN = Pattern.compile("@everyone");

    @Inject UserRepository userRepo;
    @Inject RoleRepository roleRepo;
    @Inject PermissionService permissionService;

    public record ParsedMentions(
            List<MentionedUser> users,
            List<MentionedRole> roles,
            boolean mentionEveryone
    ) {}

    public record MentionedUser(String id, String username, String displayName) {}
    public record MentionedRole(String id, String name, String color) {}

    /**
     * Parse all mentions from message content and validate them.
     * @param content Message content
     * @param authorId User creating the message (for @everyone permission check)
     * @param channelId Channel where message is sent (for @everyone permission check)
     * @return Parsed and validated mentions
     */
    public ParsedMentions parse(String content, String authorId, String channelId) {
        if (content == null || content.isBlank()) {
            return new ParsedMentions(List.of(), List.of(), false);
        }

        var users = parseUserMentions(content);
        var roles = parseRoleMentions(content);
        boolean mentionEveryone = checkEveryoneMention(content, authorId, channelId);

        return new ParsedMentions(users, roles, mentionEveryone);
    }

    private List<MentionedUser> parseUserMentions(String content) {
        var matcher = USER_MENTION_PATTERN.matcher(content);
        var userMap = new LinkedHashMap<String, MentionedUser>(); // preserve order, dedupe

        while (matcher.find()) {
            String userId = matcher.group(1);
            if (!userMap.containsKey(userId)) {
                var userOpt = userRepo.findById(userId);
                userOpt.ifPresent(u -> {
                    String username = (String) u.get("username");
                    String displayName = (String) u.get("display_name");
                    userMap.put(userId, new MentionedUser(userId, username, displayName != null ? displayName : username));
                });
                // If user not found, silently skip (invalid mention stays as text)
            }
        }

        return new ArrayList<>(userMap.values());
    }

    private List<MentionedRole> parseRoleMentions(String content) {
        var matcher = ROLE_MENTION_PATTERN.matcher(content);
        var roleMap = new LinkedHashMap<String, MentionedRole>(); // preserve order, dedupe

        while (matcher.find()) {
            String roleId = matcher.group(1);
            if (!roleMap.containsKey(roleId)) {
                var roleOpt = roleRepo.findById(roleId);
                roleOpt.ifPresent(r -> {
                    String name = (String) r.get("name");
                    String color = (String) r.get("color");
                    roleMap.put(roleId, new MentionedRole(roleId, name, color));
                });
                // If role not found, silently skip
            }
        }

        return new ArrayList<>(roleMap.values());
    }

    private boolean checkEveryoneMention(String content, String authorId, String channelId) {
        var matcher = EVERYONE_PATTERN.matcher(content);
        if (!matcher.find()) {
            return false;
        }

        // Check if user has MENTION_EVERYONE permission
        try {
            permissionService.requirePermission(authorId, channelId, Permission.MENTION_EVERYONE);
            return true;
        } catch (Exception e) {
            // No permission - @everyone will render as literal text
            return false;
        }
    }
}
