package chat.fold.security;

import chat.fold.db.DmRepository;
import chat.fold.db.RoleRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PermissionService {

    @Inject RoleRepository roleRepo;
    @Inject DmRepository dmRepo;

    private final Map<String, Long> basePermCache = new ConcurrentHashMap<>();
    private final Map<String, Long> effectivePermCache = new ConcurrentHashMap<>();
    private final Set<String> ownerCache = ConcurrentHashMap.newKeySet();
    private volatile boolean ownerCacheLoaded = false;

    /** DM participant cache: channelId → participant user IDs */
    private final Map<String, Set<String>> dmParticipantCache = new ConcurrentHashMap<>();
    /** Negative cache: channel IDs confirmed to NOT be DM channels */
    private final Set<String> notDmChannels = ConcurrentHashMap.newKeySet();

    /** Permissions granted to DM channel participants */
    private static final long DM_PERMISSIONS = Permission.VIEW_CHANNEL.value
            | Permission.SEND_MESSAGES.value
            | Permission.MANAGE_OWN_MESSAGES.value
            | Permission.UPLOAD_FILES.value
            | Permission.ADD_REACTIONS.value;

    // --- Owner checks ---

    public boolean isOwner(String userId) {
        ensureOwnerCache(userId);
        return ownerCache.contains(userId);
    }

    private void ensureOwnerCache(String userId) {
        if (!ownerCacheLoaded || !basePermCache.containsKey(userId)) {
            var roleIds = roleRepo.findUserRoleIds(userId);
            if (roleIds.contains("owner")) {
                ownerCache.add(userId);
            }
            ownerCacheLoaded = true;
        }
    }

    // --- Base permissions (OR of all roles) ---

    public long computeBasePermissions(String userId) {
        return basePermCache.computeIfAbsent(userId, uid -> {
            var roleIds = roleRepo.findUserRoleIds(uid);
            if (roleIds.contains("owner")) {
                ownerCache.add(uid);
                return Permission.ALL;
            }
            long base = 0;
            for (var roleId : roleIds) {
                var role = roleRepo.findById(roleId);
                if (role.isPresent()) {
                    base |= (Long) role.get().get("permissions");
                }
            }
            return base;
        });
    }

    // --- Effective permissions (per-role resolution, then OR) ---

    public long computeEffectivePermissions(String userId, String channelId) {
        if (isOwner(userId)) return Permission.ALL;

        String cacheKey = userId + ":" + channelId;
        return effectivePermCache.computeIfAbsent(cacheKey, key -> {
            var roleIds = roleRepo.findUserRoleIds(userId);

            // Per-role resolution: apply each role's override to its own base, then OR together.
            // This gives "highest privilege wins" — a deny on one role can't block a grant from another.
            long effective = 0;
            for (var roleId : roleIds) {
                var role = roleRepo.findById(roleId);
                if (role.isEmpty()) continue;
                long rolePerms = (Long) role.get().get("permissions");

                // ADMINISTRATOR bypasses channel overrides
                if (Permission.has(rolePerms, Permission.ADMINISTRATOR)) return Permission.ALL;

                // Apply this role's channel override (if any)
                var override = roleRepo.findRoleOverride(channelId, roleId);
                if (override.isPresent()) {
                    long allow = (Long) override.get().get("allow");
                    long deny = (Long) override.get().get("deny");
                    rolePerms = (rolePerms & ~deny) | allow;
                }
                effective |= rolePerms;
            }
            return effective;
        });
    }

    // --- Permission checks ---

    public boolean hasPermission(String userId, String channelId, Permission p) {
        // DM fast path — participant-only, no role-based bypass
        if (isDmParticipant(channelId, userId)) {
            return Permission.has(DM_PERMISSIONS, p);
        }
        // If it's a DM channel but user is NOT a participant → deny
        if (dmParticipantCache.containsKey(channelId)) {
            return false;
        }
        long effective = computeEffectivePermissions(userId, channelId);
        return Permission.has(effective, p);
    }

    public void requirePermission(String userId, String channelId, Permission p) {
        if (!hasPermission(userId, channelId, p)) {
            throw new PermissionException(p);
        }
    }

    /** Check if user is a DM channel participant (lazy-loads and caches) */
    public boolean isDmParticipant(String channelId, String userId) {
        var participants = dmParticipantCache.get(channelId);
        if (participants != null) {
            return participants.contains(userId);
        }
        // Fast negative check — already confirmed not a DM channel
        if (notDmChannels.contains(channelId)) {
            return false;
        }
        // Check if this is a DM channel at all
        if (!dmRepo.isDmChannel(channelId)) {
            notDmChannels.add(channelId);
            return false;
        }
        // Populate cache
        participants = dmRepo.findParticipants(channelId);
        dmParticipantCache.put(channelId, participants);
        return participants.contains(userId);
    }

    /** Check if a channel is a DM channel (uses cache if available) */
    public boolean isDmChannel(String channelId) {
        if (dmParticipantCache.containsKey(channelId)) return true;
        if (notDmChannels.contains(channelId)) return false;
        if (!dmRepo.isDmChannel(channelId)) {
            notDmChannels.add(channelId);
            return false;
        }
        // Populate cache while we're here
        dmParticipantCache.put(channelId, dmRepo.findParticipants(channelId));
        return true;
    }

    public boolean hasServerPermission(String userId, Permission p) {
        if (isOwner(userId)) return true;
        long base = computeBasePermissions(userId);
        if (Permission.has(base, Permission.ADMINISTRATOR)) return true;
        return Permission.has(base, p);
    }

    public void requireServerPermission(String userId, Permission p) {
        if (!hasServerPermission(userId, p)) {
            throw new PermissionException(p);
        }
    }

    // --- Filtering ---

    /** Filter channel IDs to only those the user has VIEW_CHANNEL on */
    public Set<String> filterViewableChannels(String userId, Set<String> channelIds) {
        if (isOwner(userId)) return channelIds;
        long base = computeBasePermissions(userId);
        if (Permission.has(base, Permission.ADMINISTRATOR)) return channelIds;

        var viewable = new HashSet<String>();
        for (var channelId : channelIds) {
            if (hasPermission(userId, channelId, Permission.VIEW_CHANNEL)) {
                viewable.add(channelId);
            }
        }
        return viewable;
    }

    /** Compute user_permissions payload for HELLO */
    public Map<String, Object> computeUserPermissions(String userId, Set<String> channelIds) {
        long base = computeBasePermissions(userId);
        var result = new LinkedHashMap<String, Object>();
        result.put("server", Permission.toNames(base));

        var channels = new LinkedHashMap<String, Object>();
        for (var channelId : channelIds) {
            long effective = computeEffectivePermissions(userId, channelId);
            channels.put(channelId, Permission.toNames(effective));
        }
        result.put("channels", channels);
        return result;
    }

    /** Compact bitmask permissions for HELLO — only includes channel overrides that differ from server base */
    public Map<String, Object> computeUserPermissionsBitmask(String userId, Set<String> channelIds) {
        long base = computeBasePermissions(userId);
        var result = new LinkedHashMap<String, Object>();
        result.put("server", base);

        var channels = new LinkedHashMap<String, Object>();
        for (var channelId : channelIds) {
            long effective = computeEffectivePermissions(userId, channelId);
            if (effective != base) {
                channels.put(channelId, effective);
            }
        }
        result.put("channels", channels);
        return result;
    }

    /**
     * Batch: filter viewable channels + compute bitmask permissions in one pass.
     * Loads role IDs once, bulk-loads all overrides, computes in-memory.
     * Returns {"viewable": Set<String>, "permissions": Map (server + channels bitmask)}.
     */
    public record BulkPermissionResult(Set<String> viewable, Map<String, Object> permissions) {}

    public BulkPermissionResult filterAndComputePermissionsBulk(String userId, Set<String> allChannelIds) {
        long base = computeBasePermissions(userId);
        boolean isAdmin = isOwner(userId) || Permission.has(base, Permission.ADMINISTRATOR);

        if (isAdmin) {
            var permsMap = new LinkedHashMap<String, Object>();
            permsMap.put("server", Permission.ALL);
            permsMap.put("channels", Map.of());
            return new BulkPermissionResult(allChannelIds, permsMap);
        }

        // Load user's roles + role objects once
        var roleIds = roleRepo.findUserRoleIds(userId);
        var roles = new LinkedHashMap<String, Long>(); // roleId → permissions
        for (var roleId : roleIds) {
            var role = roleRepo.findById(roleId);
            role.ifPresent(r -> roles.put(roleId, (Long) r.get("permissions")));
        }

        // Bulk-load all overrides for all channels × all roles in one query
        var allOverrides = roleRepo.findOverridesForChannelsAndRoles(allChannelIds, roleIds);
        // Index: channelId:roleId → {allow, deny}
        var overrideIndex = new HashMap<String, Map<String, Object>>();
        for (var ov : allOverrides) {
            overrideIndex.put(ov.get("channel_id") + ":" + ov.get("role_id"), ov);
        }

        var viewable = new HashSet<String>();
        var channelPerms = new LinkedHashMap<String, Object>();

        for (var channelId : allChannelIds) {
            // Check DM fast path
            if (isDmParticipant(channelId, userId)) {
                if (Permission.has(DM_PERMISSIONS, Permission.VIEW_CHANNEL)) viewable.add(channelId);
                continue;
            }
            if (dmParticipantCache.containsKey(channelId)) continue; // DM but not participant

            // Compute effective permissions from roles + overrides
            long effective = 0;
            for (var entry : roles.entrySet()) {
                long rolePerms = entry.getValue();
                if (Permission.has(rolePerms, Permission.ADMINISTRATOR)) {
                    effective = Permission.ALL;
                    break;
                }
                var override = overrideIndex.get(channelId + ":" + entry.getKey());
                if (override != null) {
                    long allow = (Long) override.get("allow");
                    long deny = (Long) override.get("deny");
                    rolePerms = (rolePerms & ~deny) | allow;
                }
                effective |= rolePerms;
            }

            // Cache for other code paths
            effectivePermCache.put(userId + ":" + channelId, effective);

            if (Permission.has(effective, Permission.VIEW_CHANNEL)) {
                viewable.add(channelId);
                if (effective != base) {
                    channelPerms.put(channelId, effective);
                }
            }
        }

        var permsMap = new LinkedHashMap<String, Object>();
        permsMap.put("server", base);
        permsMap.put("channels", channelPerms);
        return new BulkPermissionResult(viewable, permsMap);
    }

    // --- Cache invalidation ---

    public void invalidateUser(String userId) {
        basePermCache.remove(userId);
        ownerCache.remove(userId);
        // Remove all effective perm entries for this user
        effectivePermCache.keySet().removeIf(key -> key.startsWith(userId + ":"));
    }

    public void invalidateChannel(String channelId) {
        effectivePermCache.keySet().removeIf(key -> key.endsWith(":" + channelId));
        dmParticipantCache.remove(channelId);
        notDmChannels.remove(channelId);
    }

    public void invalidateAll() {
        basePermCache.clear();
        effectivePermCache.clear();
        ownerCache.clear();
        ownerCacheLoaded = false;
        dmParticipantCache.clear();
        notDmChannels.clear();
    }

    // --- Exception ---

    public static class PermissionException extends RuntimeException {
        public final Permission permission;
        public PermissionException(Permission permission) {
            super("Missing permission: " + permission.name());
            this.permission = permission;
        }
    }
}
