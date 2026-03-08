package chat.kith.service;

import chat.kith.db.CategoryRepository;
import chat.kith.db.ChannelRepository;
import chat.kith.db.RoleRepository;
import chat.kith.event.*;
import chat.kith.security.Permission;
import chat.kith.security.PermissionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import java.util.*;
import java.util.stream.Collectors;

@ApplicationScoped
public class RoleService {

    private static final String OWNER_ROLE_ID = "owner";

    @Inject RoleRepository roleRepo;
    @Inject ChannelRepository channelRepo;
    @Inject CategoryRepository categoryRepo;
    @Inject PermissionService permissionService;
    @Inject EventBus eventBus;
    @Inject SessionRegistry sessionRegistry;

    // --- Role CRUD ---

    public Map<String, Object> createRole(String actorId, String name, long permissions, String color) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_ROLES);

        int position = roleRepo.nextPosition();
        String id = UUID.randomUUID().toString();
        roleRepo.create(id, name, permissions, position, color, false);
        permissionService.invalidateAll();

        var role = roleRepo.findById(id).orElseThrow();
        var serialized = serializeRole(role);
        eventBus.publish(Event.of(EventType.ROLE_CREATE, serialized, Scope.server()));
        return serialized;
    }

    public Map<String, Object> updateRole(String actorId, String roleId, String name, Long permissions, String color) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_ROLES);
        requireNotOwner(roleId, "Cannot modify the Owner role");

        var existing = roleRepo.findById(roleId)
                .orElseThrow(() -> notFound("Role not found"));

        int existingPos = ((Long) existing.get("position")).intValue();
        checkHierarchy(actorId, existingPos);

        String newName = name != null ? name : (String) existing.get("name");
        long newPerms = permissions != null ? permissions : (Long) existing.get("permissions");
        String newColor = color != null ? color : (String) existing.get("color");

        roleRepo.update(roleId, newName, newPerms, existingPos, newColor);
        permissionService.invalidateAll();

        var updated = roleRepo.findById(roleId).orElseThrow();
        var serialized = serializeRole(updated);
        eventBus.publish(Event.of(EventType.ROLE_UPDATE, serialized, Scope.server()));

        // If permissions changed, broadcast updated user state to affected online users
        long oldPerms = (Long) existing.get("permissions");
        if (newPerms != oldPerms) {
            broadcastPermissionChange(roleId);
        }

        return serialized;
    }

    public List<Map<String, Object>> reorderRoles(String actorId, List<RoleRepository.IdPosition> items) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_ROLES);
        for (var item : items) {
            roleRepo.findById(item.id()).orElseThrow(() -> notFound("Role not found: " + item.id()));
        }
        roleRepo.batchUpdatePositions(items);
        permissionService.invalidateAll();

        var allRoles = roleRepo.findAll().stream().map(this::serializeRole).toList();
        for (var role : allRoles) {
            eventBus.publish(Event.of(EventType.ROLE_UPDATE, role, Scope.server()));
        }
        return allRoles;
    }

    public long deleteRole(String actorId, String roleId) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_ROLES);
        requireNotOwner(roleId, "Cannot delete the Owner role");

        var existing = roleRepo.findById(roleId)
                .orElseThrow(() -> notFound("Role not found"));

        int existingPos = ((Long) existing.get("position")).intValue();
        checkHierarchy(actorId, existingPos);

        long userCount = roleRepo.countUsersWithRole(roleId);
        roleRepo.delete(roleId);
        permissionService.invalidateAll();

        eventBus.publish(Event.of(EventType.ROLE_DELETE, Map.of("id", roleId), Scope.server()));
        return userCount;
    }

    // --- Role assignment ---

    public void assignRole(String actorId, String userId, String roleId) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_ROLES);
        requireNotOwner(roleId, "Cannot assign the Owner role");

        var role = roleRepo.findById(roleId)
                .orElseThrow(() -> notFound("Role not found"));
        int rolePos = ((Long) role.get("position")).intValue();
        checkHierarchy(actorId, rolePos);

        roleRepo.assignRole(userId, roleId);
        permissionService.invalidateUser(userId);

        var userState = computeUserState(userId);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("user_id", userId);
        payload.put("role_id", roleId);
        payload.put("role", serializeRole(role));
        payload.putAll(userState);
        eventBus.publish(Event.of(EventType.MEMBER_ROLE_ASSIGNED, payload, Scope.server()));
    }

    public void removeRole(String actorId, String userId, String roleId) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_ROLES);
        requireNotOwner(roleId, "Cannot remove the Owner role");

        var role = roleRepo.findById(roleId)
                .orElseThrow(() -> notFound("Role not found"));
        int rolePos = ((Long) role.get("position")).intValue();
        checkHierarchy(actorId, rolePos);

        roleRepo.removeRole(userId, roleId);
        permissionService.invalidateUser(userId);

        var userState = computeUserState(userId);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("user_id", userId);
        payload.put("role_id", roleId);
        payload.putAll(userState);
        eventBus.publish(Event.of(EventType.MEMBER_ROLE_REMOVED, payload, Scope.server()));
    }

    // --- Channel overrides ---

    public Map<String, Object> upsertOverride(String actorId, String channelId, String roleId, long allow, long deny) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_CHANNELS);

        roleRepo.findById(roleId).orElseThrow(() -> notFound("Role not found"));

        String id = UUID.randomUUID().toString();
        roleRepo.upsertOverride(id, channelId, roleId, allow, deny);
        permissionService.invalidateChannel(channelId);

        var override = roleRepo.findRoleOverride(channelId, roleId).orElseThrow();
        var serialized = serializeOverride(override);
        broadcastPermissionChange(roleId);
        return serialized;
    }

    public void deleteOverride(String actorId, String channelId, String roleId) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_CHANNELS);

        roleRepo.deleteOverride(channelId, roleId);
        permissionService.invalidateChannel(channelId);

        broadcastPermissionChange(roleId);
    }

    /** Recompute and send updated channels/permissions to all online users with the given role */
    private void broadcastPermissionChange(String roleId) {
        var onlineUserIds = sessionRegistry.onlineUserIds();
        var affectedUserIds = roleRepo.findUserIdsWithRole(roleId);
        for (var userId : affectedUserIds) {
            if (!onlineUserIds.contains(userId)) continue;
            var userState = computeUserState(userId);
            var payload = new LinkedHashMap<String, Object>();
            payload.put("user_id", userId);
            payload.putAll(userState);
            eventBus.publish(Event.of(EventType.CHANNEL_PERMISSIONS_UPDATE, payload, Scope.user(userId)));
        }
    }

    // --- Default role ---

    public Optional<Map<String, Object>> getDefaultRole() {
        return roleRepo.findDefaultRole();
    }

    public Map<String, Object> setDefaultRole(String actorId, String roleId) {
        permissionService.requireServerPermission(actorId, Permission.MANAGE_ROLES);
        requireNotOwner(roleId, "Cannot set Owner as default role");

        roleRepo.findById(roleId).orElseThrow(() -> notFound("Role not found"));

        // Capture old default before switching
        var oldDefault = roleRepo.findDefaultRole();

        roleRepo.setDefault(roleId);

        // Broadcast old default with is_default=0
        oldDefault.ifPresent(old -> {
            if (!old.get("id").equals(roleId)) {
                var refreshed = roleRepo.findById((String) old.get("id")).orElseThrow();
                eventBus.publish(Event.of(EventType.ROLE_UPDATE, serializeRole(refreshed), Scope.server()));
            }
        });

        var updated = roleRepo.findById(roleId).orElseThrow();
        var serialized = serializeRole(updated);
        eventBus.publish(Event.of(EventType.ROLE_UPDATE, serialized, Scope.server()));
        return serialized;
    }

    // --- Serialization (bitmask → names at API boundary) ---

    public Map<String, Object> serializeRole(Map<String, Object> role) {
        var result = new LinkedHashMap<>(role);
        long perms = (Long) role.get("permissions");
        result.put("permissions", Permission.toNames(perms));
        return result;
    }

    public Map<String, Object> serializeOverride(Map<String, Object> override) {
        var result = new LinkedHashMap<>(override);
        result.put("allow", Permission.toNames((Long) override.get("allow")));
        result.put("deny", Permission.toNames((Long) override.get("deny")));
        return result;
    }

    // --- Hierarchy checks ---

    /** Actor must have a role with lower position number (higher rank) than target position */
    private void checkHierarchy(String actorId, int targetPosition) {
        if (permissionService.isOwner(actorId)) return;

        var actorRoleIds = roleRepo.findUserRoleIds(actorId);
        int highestRank = Integer.MAX_VALUE;
        for (var roleId : actorRoleIds) {
            var role = roleRepo.findById(roleId);
            if (role.isPresent()) {
                int pos = ((Long) role.get().get("position")).intValue();
                highestRank = Math.min(highestRank, pos);
            }
        }
        if (highestRank >= targetPosition) {
            throw new WebApplicationException(
                    Response.status(403).entity(Map.of("error", "forbidden", "message", "Cannot manage roles at or above your rank")).build()
            );
        }
    }

    private void requireNotOwner(String roleId, String message) {
        if (OWNER_ROLE_ID.equals(roleId)) {
            throw new WebApplicationException(
                    Response.status(403).entity(Map.of("error", "forbidden", "message", message)).build()
            );
        }
    }

    private void validatePosition(int position, String excludeId) {
        if (roleRepo.existsByPosition(position, excludeId != null ? excludeId : "")) {
            throw new WebApplicationException(
                    Response.status(409).entity(Map.of("error", "position_conflict", "message", "A role with this position already exists")).build()
            );
        }
    }

    /** Compute permissions + viewable channels + visible categories for a user */
    private Map<String, Object> computeUserState(String userId) {
        var allChannels = channelRepo.listAll();
        var allChannelIds = allChannels.stream()
                .map(c -> (String) c.get("id"))
                .collect(Collectors.toSet());
        var viewableIds = permissionService.filterViewableChannels(userId, allChannelIds);
        var viewableChannels = allChannels.stream()
                .filter(c -> viewableIds.contains(c.get("id")))
                .toList();

        // Users with MANAGE_CHANNELS see all categories; others only see populated ones
        List<Map<String, Object>> categories;
        if (permissionService.hasServerPermission(userId, Permission.MANAGE_CHANNELS)) {
            categories = categoryRepo.listAll();
        } else {
            var usedCategoryIds = viewableChannels.stream()
                    .map(c -> (String) c.get("category_id"))
                    .filter(java.util.Objects::nonNull)
                    .collect(Collectors.toSet());
            categories = categoryRepo.listAll().stream()
                    .filter(cat -> usedCategoryIds.contains(cat.get("id")))
                    .toList();
        }

        var result = new LinkedHashMap<String, Object>();
        result.put("user_permissions", permissionService.computeUserPermissions(userId, viewableIds));
        result.put("channels", viewableChannels);
        result.put("categories", categories);
        return result;
    }

    private static WebApplicationException notFound(String message) {
        return new WebApplicationException(
                Response.status(404).entity(Map.of("error", "not_found", "message", message)).build()
        );
    }
}
