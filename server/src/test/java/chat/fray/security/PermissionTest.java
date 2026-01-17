package chat.fray.security;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PermissionTest {

    // --- Bit positions ---

    @Test
    void bitPositions_channelPermissions() {
        assertEquals(0, Permission.VIEW_CHANNEL.bit);
        assertEquals(1, Permission.SEND_MESSAGES.bit);
        assertEquals(2, Permission.MANAGE_OWN_MESSAGES.bit);
        assertEquals(3, Permission.MANAGE_MESSAGES.bit);
        assertEquals(4, Permission.UPLOAD_FILES.bit);
        assertEquals(5, Permission.ADD_REACTIONS.bit);
        assertEquals(6, Permission.MENTION_EVERYONE.bit);
        assertEquals(8, Permission.CREATE_THREADS.bit);
        assertEquals(9, Permission.MANAGE_OWN_THREADS.bit);
        assertEquals(10, Permission.MANAGE_THREADS.bit);
    }

    @Test
    void bitPositions_serverPermissions() {
        assertEquals(16, Permission.MANAGE_CHANNELS.bit);
        assertEquals(17, Permission.MANAGE_ROLES.bit);
        assertEquals(18, Permission.MANAGE_SERVER.bit);
        assertEquals(19, Permission.KICK_MEMBERS.bit);
        assertEquals(20, Permission.BAN_MEMBERS.bit);
        assertEquals(21, Permission.CREATE_INVITES.bit);
        assertEquals(22, Permission.MANAGE_INVITES.bit);
        assertEquals(23, Permission.CHANGE_NICKNAME.bit);
        assertEquals(24, Permission.MANAGE_NICKNAMES.bit);
        assertEquals(31, Permission.ADMINISTRATOR.bit);
    }

    @Test
    void bitPositions_voicePermissions() {
        assertEquals(32, Permission.USE_VOICE.bit);
        assertEquals(33, Permission.VIDEO.bit);
        assertEquals(34, Permission.MUTE_MEMBERS.bit);
        assertEquals(35, Permission.DEAFEN_MEMBERS.bit);
        assertEquals(36, Permission.MOVE_MEMBERS.bit);
        assertEquals(37, Permission.PRIORITY_SPEAKER.bit);
    }

    @Test
    void values_arePowersOfTwo() {
        for (var p : Permission.values()) {
            assertEquals(1L << p.bit, p.value);
            // Each value should have exactly one bit set
            assertEquals(1, Long.bitCount(p.value));
        }
    }

    @Test
    void allPermissions_haveUniqueBits() {
        var bits = new java.util.HashSet<Integer>();
        for (var p : Permission.values()) {
            assertTrue(bits.add(p.bit), "Duplicate bit position: " + p.bit);
        }
    }

    // --- has/grant/revoke ---

    @Test
    void has_returnsTrueWhenBitSet() {
        long mask = Permission.VIEW_CHANNEL.value | Permission.SEND_MESSAGES.value;
        assertTrue(Permission.has(mask, Permission.VIEW_CHANNEL));
        assertTrue(Permission.has(mask, Permission.SEND_MESSAGES));
        assertFalse(Permission.has(mask, Permission.MANAGE_MESSAGES));
    }

    @Test
    void has_worksWithZeroMask() {
        for (var p : Permission.values()) {
            assertFalse(Permission.has(0L, p));
        }
    }

    @Test
    void has_worksWithAllBitsSet() {
        for (var p : Permission.values()) {
            assertTrue(Permission.has(Permission.ALL, p));
        }
    }

    @Test
    void grant_setsCorrectBit() {
        long mask = 0;
        mask = Permission.grant(mask, Permission.VIEW_CHANNEL);
        assertEquals(Permission.VIEW_CHANNEL.value, mask);

        mask = Permission.grant(mask, Permission.SEND_MESSAGES);
        assertEquals(Permission.VIEW_CHANNEL.value | Permission.SEND_MESSAGES.value, mask);
    }

    @Test
    void grant_idempotent() {
        long mask = Permission.grant(0, Permission.VIEW_CHANNEL);
        long mask2 = Permission.grant(mask, Permission.VIEW_CHANNEL);
        assertEquals(mask, mask2);
    }

    @Test
    void revoke_clearsCorrectBit() {
        long mask = Permission.VIEW_CHANNEL.value | Permission.SEND_MESSAGES.value;
        mask = Permission.revoke(mask, Permission.VIEW_CHANNEL);
        assertFalse(Permission.has(mask, Permission.VIEW_CHANNEL));
        assertTrue(Permission.has(mask, Permission.SEND_MESSAGES));
    }

    @Test
    void revoke_idempotentOnUnsetBit() {
        long mask = Permission.SEND_MESSAGES.value;
        long mask2 = Permission.revoke(mask, Permission.VIEW_CHANNEL);
        assertEquals(mask, mask2);
    }

    // --- toNames / fromNames ---

    @Test
    void toNames_emptyForZero() {
        assertTrue(Permission.toNames(0L).isEmpty());
    }

    @Test
    void toNames_returnsSinglePermission() {
        var names = Permission.toNames(Permission.VIEW_CHANNEL.value);
        assertEquals(List.of("VIEW_CHANNEL"), names);
    }

    @Test
    void toNames_returnsMultiplePermissions() {
        long mask = Permission.VIEW_CHANNEL.value | Permission.SEND_MESSAGES.value | Permission.ADMINISTRATOR.value;
        var names = Permission.toNames(mask);
        assertEquals(3, names.size());
        assertTrue(names.contains("VIEW_CHANNEL"));
        assertTrue(names.contains("SEND_MESSAGES"));
        assertTrue(names.contains("ADMINISTRATOR"));
    }

    @Test
    void toNames_allReturnsAllPermissions() {
        var names = Permission.toNames(Permission.ALL);
        assertEquals(Permission.values().length, names.size());
    }

    @Test
    void fromNames_emptyList() {
        assertEquals(0L, Permission.fromNames(List.of()));
    }

    @Test
    void fromNames_singlePermission() {
        assertEquals(Permission.VIEW_CHANNEL.value, Permission.fromNames(List.of("VIEW_CHANNEL")));
    }

    @Test
    void fromNames_multiplePermissions() {
        long expected = Permission.VIEW_CHANNEL.value | Permission.ADMINISTRATOR.value;
        assertEquals(expected, Permission.fromNames(List.of("VIEW_CHANNEL", "ADMINISTRATOR")));
    }

    @Test
    void fromNames_invalidNameThrows() {
        assertThrows(IllegalArgumentException.class, () -> Permission.fromNames(List.of("INVALID_PERM")));
    }

    @Test
    void toNames_fromNames_roundTrip() {
        long original = Permission.VIEW_CHANNEL.value | Permission.SEND_MESSAGES.value | Permission.MANAGE_CHANNELS.value;
        var names = Permission.toNames(original);
        long result = Permission.fromNames(names);
        assertEquals(original, result);
    }

    // --- isServerLevel ---

    @Test
    void isServerLevel_trueForServerPermissions() {
        assertTrue(Permission.isServerLevel(Permission.MANAGE_CHANNELS));
        assertTrue(Permission.isServerLevel(Permission.MANAGE_ROLES));
        assertTrue(Permission.isServerLevel(Permission.MANAGE_SERVER));
        assertTrue(Permission.isServerLevel(Permission.KICK_MEMBERS));
        assertTrue(Permission.isServerLevel(Permission.BAN_MEMBERS));
        assertTrue(Permission.isServerLevel(Permission.CREATE_INVITES));
        assertTrue(Permission.isServerLevel(Permission.MANAGE_INVITES));
        assertTrue(Permission.isServerLevel(Permission.CHANGE_NICKNAME));
        assertTrue(Permission.isServerLevel(Permission.MANAGE_NICKNAMES));
        assertTrue(Permission.isServerLevel(Permission.ADMINISTRATOR));
    }

    @Test
    void isServerLevel_falseForChannelPermissions() {
        assertFalse(Permission.isServerLevel(Permission.VIEW_CHANNEL));
        assertFalse(Permission.isServerLevel(Permission.SEND_MESSAGES));
        assertFalse(Permission.isServerLevel(Permission.MANAGE_OWN_MESSAGES));
        assertFalse(Permission.isServerLevel(Permission.MANAGE_MESSAGES));
        assertFalse(Permission.isServerLevel(Permission.UPLOAD_FILES));
        assertFalse(Permission.isServerLevel(Permission.ADD_REACTIONS));
    }

    @Test
    void isServerLevel_falseForVoicePermissions() {
        assertFalse(Permission.isServerLevel(Permission.USE_VOICE));
        assertFalse(Permission.isServerLevel(Permission.VIDEO));
        assertFalse(Permission.isServerLevel(Permission.MUTE_MEMBERS));
        assertFalse(Permission.isServerLevel(Permission.DEAFEN_MEMBERS));
        assertFalse(Permission.isServerLevel(Permission.MOVE_MEMBERS));
        assertFalse(Permission.isServerLevel(Permission.PRIORITY_SPEAKER));
    }

    // --- ALL constant ---

    @Test
    void all_hasAllBitsSet() {
        assertEquals(-1L, Permission.ALL);
        assertEquals(64, Long.bitCount(Permission.ALL));
    }

    // --- 64-bit boundary ---

    @Test
    void voicePermissions_use64BitRange() {
        // Voice permissions are at bits 32-37 — requires long (>32-bit)
        assertTrue(Permission.USE_VOICE.value > Integer.MAX_VALUE);
        assertTrue(Permission.PRIORITY_SPEAKER.value > Integer.MAX_VALUE);
    }

    @Test
    void grant_revoke_worksWithHighBits() {
        long mask = Permission.grant(0, Permission.PRIORITY_SPEAKER);
        assertTrue(Permission.has(mask, Permission.PRIORITY_SPEAKER));
        mask = Permission.revoke(mask, Permission.PRIORITY_SPEAKER);
        assertFalse(Permission.has(mask, Permission.PRIORITY_SPEAKER));
        assertEquals(0, mask);
    }
}
