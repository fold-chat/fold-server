package chat.fold.security;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 64-bit bitmask permissions. Each enum value maps to a single bit position.
 * See docs/permissions.md for full documentation.
 */
public enum Permission {

    // Channel permissions (bits 0-15)
    VIEW_CHANNEL(0),
    SEND_MESSAGES(1),
    MANAGE_OWN_MESSAGES(2),
    MANAGE_MESSAGES(3),
    UPLOAD_FILES(4),
    ADD_REACTIONS(5),
    MENTION_EVERYONE(6),
    // bit 7 reserved
    CREATE_THREADS(8),
    MANAGE_OWN_THREADS(9),
    MANAGE_THREADS(10),
    SEND_IN_LOCKED_THREADS(11),
    // bits 12-15 reserved

    // Server permissions (bits 16-31)
    MANAGE_CHANNELS(16),
    MANAGE_ROLES(17),
    MANAGE_SERVER(18),
    // bit 19 reserved (was KICK_MEMBERS)
    BAN_MEMBERS(20),
    CREATE_INVITES(21),
    MANAGE_INVITES(22),
    CHANGE_NICKNAME(23),
    MANAGE_NICKNAMES(24),
    RESET_PASSWORDS(25),
    // bits 26-30 reserved
    ADMINISTRATOR(31),

    // Voice permissions (bits 32-47)
    USE_VOICE(32),
    VIDEO(33),
    MUTE_MEMBERS(34),
    DEAFEN_MEMBERS(35),
    MOVE_MEMBERS(36),
    PRIORITY_SPEAKER(37);
    // bits 38-47 reserved
    // bits 48-63 reserved for future

    public final int bit;
    public final long value;

    Permission(int bit) {
        this.bit = bit;
        this.value = 1L << bit;
    }

    /** All permissions that are server-level (shouldn't appear in channel override UI) */
    private static final EnumSet<Permission> SERVER_LEVEL = EnumSet.of(
            MANAGE_CHANNELS, MANAGE_ROLES, MANAGE_SERVER,
            BAN_MEMBERS, CREATE_INVITES, MANAGE_INVITES,
            CHANGE_NICKNAME, MANAGE_NICKNAMES, RESET_PASSWORDS, ADMINISTRATOR
    );

    public static boolean has(long bitmask, Permission p) {
        return (bitmask & p.value) != 0;
    }

    public static long grant(long bitmask, Permission p) {
        return bitmask | p.value;
    }

    public static long revoke(long bitmask, Permission p) {
        return bitmask & ~p.value;
    }

    public static boolean isServerLevel(Permission p) {
        return SERVER_LEVEL.contains(p);
    }

    /** Convert bitmask to list of permission name strings */
    public static List<String> toNames(long bitmask) {
        var names = new ArrayList<String>();
        for (var p : values()) {
            if (has(bitmask, p)) names.add(p.name());
        }
        return names;
    }

    /** Convert list of permission name strings to bitmask */
    public static long fromNames(List<String> names) {
        long mask = 0;
        for (var name : names) {
            mask |= Permission.valueOf(name).value;
        }
        return mask;
    }

    /** All bits set — used for owner bypass */
    public static final long ALL = -1L; // all 64 bits set
}
