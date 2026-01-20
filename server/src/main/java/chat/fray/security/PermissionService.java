package chat.fray.security;

import chat.fray.db.RoleRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@ApplicationScoped
public class PermissionService {

    @Inject RoleRepository roleRepo;

    private final Map<String, Long> basePermCache = new ConcurrentHashMap<>();
    private final Map<String, Long> effectivePermCache = new ConcurrentHashMap<>();
    private final Set<String> ownerCache = ConcurrentHashMap.newKeySet();
    private volatile boolean ownerCacheLoaded = false;

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
        long effective = computeEffectivePermissions(userId, channelId);
        return Permission.has(effective, p);
    }

    public void requirePermission(String userId, String channelId, Permission p) {
        if (!hasPermission(userId, channelId, p)) {
            throw new PermissionException(p);
        }
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

    // --- Cache invalidation ---

    public void invalidateUser(String userId) {
        basePermCache.remove(userId);
        ownerCache.remove(userId);
        // Remove all effective perm entries for this user
        effectivePermCache.keySet().removeIf(key -> key.startsWith(userId + ":"));
    }

    public void invalidateChannel(String channelId) {
        effectivePermCache.keySet().removeIf(key -> key.endsWith(":" + channelId));
    }

    public void invalidateAll() {
        basePermCache.clear();
        effectivePermCache.clear();
        ownerCache.clear();
        ownerCacheLoaded = false;
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
