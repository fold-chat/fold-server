package chat.fold.security;

import chat.fold.db.DmRepository;
import chat.fold.db.RoleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PermissionServiceTest {

    private RoleRepository roleRepo;
    private DmRepository dmRepo;
    private PermissionService service;

    // Permission combos for readability
    private static final long VIEW_SEND = Permission.VIEW_CHANNEL.value | Permission.SEND_MESSAGES.value;
    private static final long VIEW_ONLY = Permission.VIEW_CHANNEL.value;
    private static final long MANAGE_OWN = Permission.MANAGE_OWN_MESSAGES.value;

    @BeforeEach
    void setup() throws Exception {
        roleRepo = mock(RoleRepository.class);
        dmRepo = mock(DmRepository.class);
        service = new PermissionService();
        // Inject mocks via reflection (no CDI in unit tests)
        var field = PermissionService.class.getDeclaredField("roleRepo");
        field.setAccessible(true);
        field.set(service, roleRepo);
        var dmField = PermissionService.class.getDeclaredField("dmRepo");
        dmField.setAccessible(true);
        dmField.set(service, dmRepo);
    }

    // --- Owner bypass ---

    @Test
    void owner_bypassesAllPermissionChecks() {
        when(roleRepo.findUserRoleIds("owner-user")).thenReturn(List.of("owner", "member"));
        assertTrue(service.isOwner("owner-user"));
        assertTrue(service.hasPermission("owner-user", "ch1", Permission.MANAGE_MESSAGES));
        assertTrue(service.hasServerPermission("owner-user", Permission.ADMINISTRATOR));
    }

    @Test
    void owner_computeBasePermissions_returnsAll() {
        when(roleRepo.findUserRoleIds("owner-user")).thenReturn(List.of("owner"));
        assertEquals(Permission.ALL, service.computeBasePermissions("owner-user"));
    }

    @Test
    void owner_effectivePermissions_returnsAll() {
        when(roleRepo.findUserRoleIds("owner-user")).thenReturn(List.of("owner"));
        assertEquals(Permission.ALL, service.computeEffectivePermissions("owner-user", "ch1"));
    }

    @Test
    void owner_ignoresChannelDeny() {
        // Even with deny overrides, owner should bypass
        when(roleRepo.findUserRoleIds("owner-user")).thenReturn(List.of("owner"));
        assertTrue(service.hasPermission("owner-user", "ch1", Permission.SEND_MESSAGES));
    }

    // --- ADMINISTRATOR bypass ---

    @Test
    void administrator_bypassesChannelOverrides() {
        when(roleRepo.findUserRoleIds("admin-user")).thenReturn(List.of("admin"));
        when(roleRepo.findById("admin")).thenReturn(Optional.of(Map.of("permissions", Permission.ADMINISTRATOR.value)));

        long effective = service.computeEffectivePermissions("admin-user", "ch1");
        assertEquals(Permission.ALL, effective);
    }

    @Test
    void administrator_hasServerPermission() {
        when(roleRepo.findUserRoleIds("admin-user")).thenReturn(List.of("admin"));
        when(roleRepo.findById("admin")).thenReturn(Optional.of(Map.of("permissions", Permission.ADMINISTRATOR.value)));
        // ADMINISTRATOR grants all server permissions
        assertTrue(service.hasServerPermission("admin-user", Permission.MANAGE_ROLES));
        assertTrue(service.hasServerPermission("admin-user", Permission.BAN_MEMBERS));
    }

    // --- Base permission resolution (OR of roles) ---

    @Test
    void basePermissions_orOfMultipleRoles() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("role-a", "role-b"));
        when(roleRepo.findById("role-a")).thenReturn(Optional.of(Map.of("permissions", Permission.VIEW_CHANNEL.value)));
        when(roleRepo.findById("role-b")).thenReturn(Optional.of(Map.of("permissions", Permission.SEND_MESSAGES.value)));

        long base = service.computeBasePermissions("user1");
        assertTrue(Permission.has(base, Permission.VIEW_CHANNEL));
        assertTrue(Permission.has(base, Permission.SEND_MESSAGES));
        assertFalse(Permission.has(base, Permission.MANAGE_MESSAGES));
    }

    @Test
    void basePermissions_noRoles_returnsZero() {
        when(roleRepo.findUserRoleIds("noroles")).thenReturn(List.of());
        assertEquals(0L, service.computeBasePermissions("noroles"));
    }

    @Test
    void basePermissions_missingRoleId_skips() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("exists", "deleted"));
        when(roleRepo.findById("exists")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        when(roleRepo.findById("deleted")).thenReturn(Optional.empty());

        long base = service.computeBasePermissions("user1");
        assertEquals(VIEW_SEND, base);
    }

    // --- Channel overrides ---

    @Test
    void channelOverride_denyRemovesBit() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        when(roleRepo.findRoleOverride("ch1", "member"))
                .thenReturn(Optional.of(Map.of("allow", 0L, "deny", Permission.SEND_MESSAGES.value)));

        long effective = service.computeEffectivePermissions("user1", "ch1");
        assertTrue(Permission.has(effective, Permission.VIEW_CHANNEL));
        assertFalse(Permission.has(effective, Permission.SEND_MESSAGES));
    }

    @Test
    void channelOverride_allowAddsBit() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_ONLY)));
        when(roleRepo.findRoleOverride("ch1", "member"))
                .thenReturn(Optional.of(Map.of("allow", Permission.MANAGE_MESSAGES.value, "deny", 0L)));

        long effective = service.computeEffectivePermissions("user1", "ch1");
        assertTrue(Permission.has(effective, Permission.VIEW_CHANNEL));
        assertTrue(Permission.has(effective, Permission.MANAGE_MESSAGES));
    }

    @Test
    void channelOverride_noOverrides_returnsBase() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        when(roleRepo.findRoleOverride("ch1", "member")).thenReturn(Optional.empty());

        assertEquals(VIEW_SEND, service.computeEffectivePermissions("user1", "ch1"));
    }

    @Test
    void channelOverride_conflictingRoles_allowWins() {
        // Per-role resolution: each role's override applied to its own base, then OR together
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("role-a", "role-b"));
        when(roleRepo.findById("role-a")).thenReturn(Optional.of(Map.of("permissions", VIEW_ONLY)));
        when(roleRepo.findById("role-b")).thenReturn(Optional.of(Map.of("permissions", VIEW_ONLY)));
        when(roleRepo.findRoleOverride("ch1", "role-a"))
                .thenReturn(Optional.of(Map.of("allow", 0L, "deny", Permission.SEND_MESSAGES.value)));
        when(roleRepo.findRoleOverride("ch1", "role-b"))
                .thenReturn(Optional.of(Map.of("allow", Permission.SEND_MESSAGES.value, "deny", 0L)));

        long effective = service.computeEffectivePermissions("user1", "ch1");
        assertTrue(Permission.has(effective, Permission.SEND_MESSAGES));
    }

    @Test
    void channelOverride_denyWithNoAllow_revokesFromBase() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions",
                VIEW_SEND | Permission.MANAGE_MESSAGES.value)));
        when(roleRepo.findRoleOverride("ch1", "member"))
                .thenReturn(Optional.of(Map.of("allow", 0L, "deny", Permission.MANAGE_MESSAGES.value)));

        long effective = service.computeEffectivePermissions("user1", "ch1");
        assertTrue(Permission.has(effective, Permission.VIEW_CHANNEL));
        assertTrue(Permission.has(effective, Permission.SEND_MESSAGES));
        assertFalse(Permission.has(effective, Permission.MANAGE_MESSAGES));
    }

    // --- requirePermission / requireServerPermission ---

    @Test
    void requirePermission_throwsWhenDenied() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_ONLY)));
        when(roleRepo.findRoleOverride("ch1", "member")).thenReturn(Optional.empty());

        assertThrows(PermissionService.PermissionException.class,
                () -> service.requirePermission("user1", "ch1", Permission.MANAGE_MESSAGES));
    }

    @Test
    void requirePermission_passesWhenGranted() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        when(roleRepo.findRoleOverride("ch1", "member")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.requirePermission("user1", "ch1", Permission.SEND_MESSAGES));
    }

    @Test
    void requireServerPermission_throwsWhenDenied() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));

        assertThrows(PermissionService.PermissionException.class,
                () -> service.requireServerPermission("user1", Permission.MANAGE_ROLES));
    }

    @Test
    void requireServerPermission_passesForOwner() {
        when(roleRepo.findUserRoleIds("owner-user")).thenReturn(List.of("owner"));

        assertDoesNotThrow(() -> service.requireServerPermission("owner-user", Permission.MANAGE_ROLES));
    }

    // --- Self-management permissions ---

    @Test
    void manageOwnMessages_separateFromManageMessages() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND | MANAGE_OWN)));
        when(roleRepo.findRoleOverride("ch1", "member")).thenReturn(Optional.empty());

        assertTrue(service.hasPermission("user1", "ch1", Permission.MANAGE_OWN_MESSAGES));
        assertFalse(service.hasPermission("user1", "ch1", Permission.MANAGE_MESSAGES));
    }

    // --- Cache invalidation ---

    @Test
    void invalidateUser_clearsCachedPermissions() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_ONLY)));
        when(roleRepo.findRoleOverride("ch1", "member")).thenReturn(Optional.empty());

        // Prime the cache
        service.computeBasePermissions("user1");
        service.computeEffectivePermissions("user1", "ch1");

        // Change role permissions
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        service.invalidateUser("user1");

        // Should re-query and get updated permissions
        long base = service.computeBasePermissions("user1");
        assertTrue(Permission.has(base, Permission.SEND_MESSAGES));
    }

    @Test
    void invalidateChannel_clearsChannelCache() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        when(roleRepo.findRoleOverride("ch1", "member"))
                .thenReturn(Optional.of(Map.of("allow", 0L, "deny", Permission.SEND_MESSAGES.value)));

        // Prime cache
        assertFalse(service.hasPermission("user1", "ch1", Permission.SEND_MESSAGES));

        // Remove override
        when(roleRepo.findRoleOverride("ch1", "member")).thenReturn(Optional.empty());
        service.invalidateChannel("ch1");

        // Should re-query
        assertTrue(service.hasPermission("user1", "ch1", Permission.SEND_MESSAGES));
    }

    @Test
    void invalidateAll_clearsEverything() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_ONLY)));
        when(roleRepo.findOverridesForRoles(eq("ch1"), any())).thenReturn(List.of());

        service.computeBasePermissions("user1");

        // Change permissions
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        service.invalidateAll();

        long base = service.computeBasePermissions("user1");
        assertTrue(Permission.has(base, Permission.SEND_MESSAGES));
    }

    // --- filterViewableChannels ---

    @Test
    void filterViewableChannels_ownerSeesAll() {
        when(roleRepo.findUserRoleIds("owner-user")).thenReturn(List.of("owner"));
        var channels = Set.of("ch1", "ch2", "ch3");
        assertEquals(channels, service.filterViewableChannels("owner-user", channels));
    }

    @Test
    void filterViewableChannels_administratorSeesAll() {
        when(roleRepo.findUserRoleIds("admin-user")).thenReturn(List.of("admin"));
        when(roleRepo.findById("admin")).thenReturn(Optional.of(Map.of("permissions", Permission.ADMINISTRATOR.value)));
        var channels = Set.of("ch1", "ch2", "ch3");
        assertEquals(channels, service.filterViewableChannels("admin-user", channels));
    }

    @Test
    void filterViewableChannels_filtersBasedOnViewChannel() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_ONLY)));
        // ch1: no override (base has VIEW_CHANNEL), ch2: deny VIEW_CHANNEL
        when(roleRepo.findRoleOverride("ch1", "member")).thenReturn(Optional.empty());
        when(roleRepo.findRoleOverride("ch2", "member"))
                .thenReturn(Optional.of(Map.of("allow", 0L, "deny", Permission.VIEW_CHANNEL.value)));

        var result = service.filterViewableChannels("user1", Set.of("ch1", "ch2"));
        assertEquals(Set.of("ch1"), result);
    }

    // --- computeUserPermissions ---

    @Test
    void computeUserPermissions_returnsServerAndChannelPerms() {
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("member"));
        long basePerm = VIEW_SEND | Permission.CREATE_INVITES.value;
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", basePerm)));
        when(roleRepo.findRoleOverride("ch1", "member")).thenReturn(Optional.empty());

        var result = service.computeUserPermissions("user1", Set.of("ch1"));

        @SuppressWarnings("unchecked")
        var serverPerms = (List<String>) result.get("server");
        assertTrue(serverPerms.contains("VIEW_CHANNEL"));
        assertTrue(serverPerms.contains("SEND_MESSAGES"));
        assertTrue(serverPerms.contains("CREATE_INVITES"));

        @SuppressWarnings("unchecked")
        var channelPerms = (Map<String, List<String>>) result.get("channels");
        assertTrue(channelPerms.containsKey("ch1"));
        assertTrue(channelPerms.get("ch1").contains("VIEW_CHANNEL"));
    }

    // --- Multi-role: highest privilege wins ---

    @Test
    void channelOverride_multiRole_highestPrivilegeWins() {
        // Guest denied VIEW on channel, member has no override → member's base wins
        when(roleRepo.findUserRoleIds("user1")).thenReturn(List.of("guest", "member"));
        when(roleRepo.findById("guest")).thenReturn(Optional.of(Map.of("permissions", VIEW_ONLY)));
        when(roleRepo.findById("member")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        when(roleRepo.findRoleOverride("secret-ch", "guest"))
                .thenReturn(Optional.of(Map.of("allow", 0L, "deny", Permission.VIEW_CHANNEL.value)));
        when(roleRepo.findRoleOverride("secret-ch", "member")).thenReturn(Optional.empty());

        long effective = service.computeEffectivePermissions("user1", "secret-ch");
        assertTrue(Permission.has(effective, Permission.VIEW_CHANNEL));
        assertTrue(Permission.has(effective, Permission.SEND_MESSAGES));
    }

    // --- PermissionException ---

    @Test
    void permissionException_containsPermission() {
        var ex = new PermissionService.PermissionException(Permission.MANAGE_ROLES);
        assertEquals(Permission.MANAGE_ROLES, ex.permission);
        assertTrue(ex.getMessage().contains("MANAGE_ROLES"));
    }
}
