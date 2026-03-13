package chat.fold.service;

import chat.fold.db.CategoryRepository;
import chat.fold.db.ChannelRepository;
import chat.fold.db.RoleRepository;
import chat.fold.event.EventBus;
import chat.fold.event.SessionRegistry;
import chat.fold.event.EventType;
import chat.fold.security.Permission;
import chat.fold.security.PermissionService;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RoleServiceTest {

    private RoleRepository roleRepo;
    private ChannelRepository channelRepo;
    private CategoryRepository categoryRepo;
    private PermissionService permissionService;
    private EventBus eventBus;
    private SessionRegistry sessionRegistry;
    private RoleService roleService;

    private static final long VIEW_SEND = Permission.VIEW_CHANNEL.value | Permission.SEND_MESSAGES.value;

    @BeforeEach
    void setup() throws Exception {
        roleRepo = mock(RoleRepository.class);
        channelRepo = mock(ChannelRepository.class);
        categoryRepo = mock(CategoryRepository.class);
        permissionService = mock(PermissionService.class);
        eventBus = mock(EventBus.class);
        sessionRegistry = mock(SessionRegistry.class);

        when(channelRepo.listAll()).thenReturn(List.of());
        when(categoryRepo.listAll()).thenReturn(List.of());
        when(sessionRegistry.onlineUserIds()).thenReturn(Set.of());

        roleService = new RoleService();
        inject(roleService, "roleRepo", roleRepo);
        inject(roleService, "channelRepo", channelRepo);
        inject(roleService, "categoryRepo", categoryRepo);
        inject(roleService, "permissionService", permissionService);
        inject(roleService, "eventBus", eventBus);
        inject(roleService, "sessionRegistry", sessionRegistry);
    }

    private void inject(Object target, String fieldName, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    // --- Owner protection ---

    @Test
    void updateRole_rejectsOwnerRole() {
        doNothing().when(permissionService).requireServerPermission(any(), any());

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.updateRole("actor", "owner", "NewName", null, null));
        assertEquals(403, ex.getResponse().getStatus());
    }

    @Test
    void deleteRole_rejectsOwnerRole() {
        doNothing().when(permissionService).requireServerPermission(any(), any());

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.deleteRole("actor", "owner"));
        assertEquals(403, ex.getResponse().getStatus());
    }

    @Test
    void assignRole_rejectsOwnerRole() {
        doNothing().when(permissionService).requireServerPermission(any(), any());

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.assignRole("actor", "user1", "owner"));
        assertEquals(403, ex.getResponse().getStatus());
    }

    @Test
    void removeRole_rejectsOwnerRole() {
        doNothing().when(permissionService).requireServerPermission(any(), any());

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.removeRole("actor", "user1", "owner"));
        assertEquals(403, ex.getResponse().getStatus());
    }

    // --- Hierarchy enforcement ---

    @Test
    void createRole_success() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(roleRepo.nextPosition()).thenReturn(5);
        when(roleRepo.findById(any())).thenReturn(Optional.of(Map.of(
                "id", "new-id", "name", "NewRole", "permissions", VIEW_SEND,
                "position", 5L, "color", "#fff", "is_default", 0L,
                "created_at", "2025-01-01")));

        assertDoesNotThrow(() -> roleService.createRole("actor", "NewRole", VIEW_SEND, "#fff"));
    }

    @Test
    void deleteRole_rejectsIfRoleAboveActor() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(false);
        when(roleRepo.findUserRoleIds("actor")).thenReturn(List.of("mod"));
        when(roleRepo.findById("mod")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 3L)));
        // Target at position 2 (higher rank)
        when(roleRepo.findById("target")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 2L)));

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.deleteRole("actor", "target"));
        assertEquals(403, ex.getResponse().getStatus());
    }

    @Test
    void updateRole_rejectsIfRoleAboveActor() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(false);
        when(roleRepo.findUserRoleIds("actor")).thenReturn(List.of("mod"));
        when(roleRepo.findById("mod")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 3L)));
        var target = new HashMap<String, Object>();
        target.put("name", "Admin");
        target.put("permissions", VIEW_SEND);
        target.put("position", 2L);
        target.put("color", null);
        when(roleRepo.findById("target")).thenReturn(Optional.of(target));

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.updateRole("actor", "target", "NewName", null, null));
        assertEquals(403, ex.getResponse().getStatus());
    }

    @Test
    void assignRole_rejectsIfRoleAboveActor() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(false);
        when(roleRepo.findUserRoleIds("actor")).thenReturn(List.of("mod"));
        when(roleRepo.findById("mod")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 3L)));
        when(roleRepo.findById("target")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 2L)));

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.assignRole("actor", "user1", "target"));
        assertEquals(403, ex.getResponse().getStatus());
    }

    @Test
    void removeRole_rejectsIfRoleAboveActor() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(false);
        when(roleRepo.findUserRoleIds("actor")).thenReturn(List.of("mod"));
        when(roleRepo.findById("mod")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 3L)));
        when(roleRepo.findById("target")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 2L)));

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.removeRole("actor", "user1", "target"));
        assertEquals(403, ex.getResponse().getStatus());
    }

    // --- Cache invalidation ---

    @Test
    void createRole_invalidatesAllPermissions() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(roleRepo.nextPosition()).thenReturn(5);
        when(roleRepo.findById(any())).thenReturn(Optional.of(Map.of(
                "id", "new-id", "name", "Test", "permissions", VIEW_SEND,
                "position", 5L, "color", "#000", "is_default", 0L,
                "created_at", "2025-01-01")));

        roleService.createRole("actor", "Test", VIEW_SEND, "#000");
        verify(permissionService).invalidateAll();
    }

    @Test
    void deleteRole_invalidatesAllPermissions() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(true);
        when(roleRepo.findById("target")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 5L)));
        when(roleRepo.countUsersWithRole("target")).thenReturn(3L);

        roleService.deleteRole("actor", "target");
        verify(permissionService).invalidateAll();
    }

    @Test
    void assignRole_invalidatesUserPermissions() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(true);
        when(roleRepo.findById("role1")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 5L)));

        roleService.assignRole("actor", "user1", "role1");
        verify(permissionService).invalidateUser("user1");
    }

    @Test
    void removeRole_invalidatesUserPermissions() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(true);
        when(roleRepo.findById("role1")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 5L)));

        roleService.removeRole("actor", "user1", "role1");
        verify(permissionService).invalidateUser("user1");
    }

    @Test
    void upsertOverride_invalidatesChannelPermissions() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(roleRepo.findById("role1")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND)));
        when(roleRepo.findRoleOverride("ch1", "role1")).thenReturn(Optional.of(Map.of(
                "id", "ov1", "channel_id", "ch1", "role_id", "role1",
                "allow", Permission.SEND_MESSAGES.value, "deny", 0L)));

        roleService.upsertOverride("actor", "ch1", "role1", Permission.SEND_MESSAGES.value, 0L);
        verify(permissionService).invalidateChannel("ch1");
    }

    @Test
    void deleteOverride_invalidatesChannelPermissions() {
        doNothing().when(permissionService).requireServerPermission(any(), any());

        roleService.deleteOverride("actor", "ch1", "role1");
        verify(permissionService).invalidateChannel("ch1");
    }

    // --- Events emitted ---

    @Test
    void createRole_emitsRoleCreateEvent() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(roleRepo.nextPosition()).thenReturn(5);
        when(roleRepo.findById(any())).thenReturn(Optional.of(Map.of(
                "id", "new-id", "name", "Test", "permissions", VIEW_SEND,
                "position", 5L, "color", "#000", "is_default", 0L,
                "created_at", "2025-01-01")));

        roleService.createRole("actor", "Test", VIEW_SEND, "#000");

        var captor = ArgumentCaptor.forClass(chat.fold.event.Event.class);
        verify(eventBus).publish(captor.capture());
        assertEquals(EventType.ROLE_CREATE, captor.getValue().type());
    }

    @Test
    void deleteRole_emitsRoleDeleteEvent() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(true);
        when(roleRepo.findById("target")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 5L)));
        when(roleRepo.countUsersWithRole("target")).thenReturn(0L);

        roleService.deleteRole("actor", "target");

        var captor = ArgumentCaptor.forClass(chat.fold.event.Event.class);
        verify(eventBus).publish(captor.capture());
        assertEquals(EventType.ROLE_DELETE, captor.getValue().type());
    }

    @Test
    void assignRole_emitsMemberRoleAssignedEvent() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(true);
        when(roleRepo.findById("role1")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 5L)));

        roleService.assignRole("actor", "user1", "role1");

        var captor = ArgumentCaptor.forClass(chat.fold.event.Event.class);
        verify(eventBus).publish(captor.capture());
        assertEquals(EventType.MEMBER_ROLE_ASSIGNED, captor.getValue().type());
    }

    @Test
    void removeRole_emitsMemberRoleRemovedEvent() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(permissionService.isOwner("actor")).thenReturn(true);
        when(roleRepo.findById("role1")).thenReturn(Optional.of(Map.of("permissions", VIEW_SEND, "position", 5L)));

        roleService.removeRole("actor", "user1", "role1");

        var captor = ArgumentCaptor.forClass(chat.fold.event.Event.class);
        verify(eventBus).publish(captor.capture());
        assertEquals(EventType.MEMBER_ROLE_REMOVED, captor.getValue().type());
    }

    // --- Serialization ---

    @Test
    void serializeRole_convertsBitmaskToNames() {
        long perms = Permission.VIEW_CHANNEL.value | Permission.SEND_MESSAGES.value;
        var role = new HashMap<String, Object>();
        role.put("id", "r1");
        role.put("name", "Member");
        role.put("permissions", perms);

        var result = roleService.serializeRole(role);

        @SuppressWarnings("unchecked")
        var permNames = (List<String>) result.get("permissions");
        assertTrue(permNames.contains("VIEW_CHANNEL"));
        assertTrue(permNames.contains("SEND_MESSAGES"));
        assertEquals(2, permNames.size());
        // Original fields preserved
        assertEquals("r1", result.get("id"));
        assertEquals("Member", result.get("name"));
    }

    @Test
    void serializeOverride_convertsBothAllowAndDeny() {
        var override = new HashMap<String, Object>();
        override.put("id", "o1");
        override.put("channel_id", "ch1");
        override.put("role_id", "r1");
        override.put("allow", Permission.SEND_MESSAGES.value);
        override.put("deny", Permission.MANAGE_MESSAGES.value);

        var result = roleService.serializeOverride(override);

        @SuppressWarnings("unchecked")
        var allowNames = (List<String>) result.get("allow");
        @SuppressWarnings("unchecked")
        var denyNames = (List<String>) result.get("deny");

        assertEquals(List.of("SEND_MESSAGES"), allowNames);
        assertEquals(List.of("MANAGE_MESSAGES"), denyNames);
    }

    @Test
    void serializeRole_zeroPermissions_emptyList() {
        var role = Map.<String, Object>of("id", "r1", "name", "Empty", "permissions", 0L);
        var result = roleService.serializeRole(role);

        @SuppressWarnings("unchecked")
        var permNames = (List<String>) result.get("permissions");
        assertTrue(permNames.isEmpty());
    }

    // --- Not found ---

    @Test
    void updateRole_throwsNotFoundForMissingRole() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(roleRepo.findById("missing")).thenReturn(Optional.empty());

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.updateRole("actor", "missing", "Name", null, null));
        assertEquals(404, ex.getResponse().getStatus());
    }

    @Test
    void deleteRole_throwsNotFoundForMissingRole() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(roleRepo.findById("missing")).thenReturn(Optional.empty());

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.deleteRole("actor", "missing"));
        assertEquals(404, ex.getResponse().getStatus());
    }

    @Test
    void assignRole_throwsNotFoundForMissingRole() {
        doNothing().when(permissionService).requireServerPermission(any(), any());
        when(roleRepo.findById("missing")).thenReturn(Optional.empty());

        var ex = assertThrows(WebApplicationException.class,
                () -> roleService.assignRole("actor", "user1", "missing"));
        assertEquals(404, ex.getResponse().getStatus());
    }

    // --- Permission checks on entry points ---

    @Test
    void createRole_requiresManageRoles() {
        doThrow(new PermissionService.PermissionException(Permission.MANAGE_ROLES))
                .when(permissionService).requireServerPermission("actor", Permission.MANAGE_ROLES);

        assertThrows(PermissionService.PermissionException.class,
                () -> roleService.createRole("actor", "Test", 0L, null));
    }

    @Test
    void upsertOverride_requiresManageChannels() {
        doThrow(new PermissionService.PermissionException(Permission.MANAGE_CHANNELS))
                .when(permissionService).requireServerPermission("actor", Permission.MANAGE_CHANNELS);

        assertThrows(PermissionService.PermissionException.class,
                () -> roleService.upsertOverride("actor", "ch1", "r1", 0L, 0L));
    }

    @Test
    void deleteOverride_requiresManageChannels() {
        doThrow(new PermissionService.PermissionException(Permission.MANAGE_CHANNELS))
                .when(permissionService).requireServerPermission("actor", Permission.MANAGE_CHANNELS);

        assertThrows(PermissionService.PermissionException.class,
                () -> roleService.deleteOverride("actor", "ch1", "r1"));
    }

    // --- Default role ---

    @Test
    void getDefaultRole_delegatesToRepo() {
        var defaultRole = Map.<String, Object>of("id", "member", "name", "Member", "is_default", 1L);
        when(roleRepo.findDefaultRole()).thenReturn(Optional.of(defaultRole));

        var result = roleService.getDefaultRole();
        assertTrue(result.isPresent());
        assertEquals("member", result.get().get("id"));
    }

    @Test
    void getDefaultRole_emptyIfNoneSet() {
        when(roleRepo.findDefaultRole()).thenReturn(Optional.empty());
        assertTrue(roleService.getDefaultRole().isEmpty());
    }
}
