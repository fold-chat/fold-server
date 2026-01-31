# Kith Permissions

Permissions are stored as 64-bit bitmasks. Each bit represents a permission.

## Channel Permissions (bits 0-15)
- Bit 0: VIEW_CHANNEL - View channel and read messages (includes message history)
- Bit 1: SEND_MESSAGES - Send messages in channel
- Bit 2: MANAGE_OWN_MESSAGES - Edit/delete own messages
- Bit 3: MANAGE_MESSAGES - Edit/delete/pin any message (including others')
- Bit 4: UPLOAD_FILES - Upload files/attachments
- Bit 5: ADD_REACTIONS - Add emoji reactions to messages
- Bit 6: MENTION_EVERYONE - Use @everyone/@here mentions
- Bit 7: Reserved
- Bit 8: CREATE_THREADS - Create threads from messages
- Bit 9: MANAGE_OWN_THREADS - Lock/archive/delete own threads
- Bit 10: MANAGE_THREADS - Lock/archive/delete any thread (including others')
- Bit 11: SEND_IN_LOCKED_THREADS - Send messages in locked threads
- Bits 12-15: Reserved

## Server Permissions (bits 16-31)
- Bit 16: MANAGE_CHANNELS - Create/edit/delete channels/categories
- Bit 17: MANAGE_ROLES - Create/edit/delete roles, assign roles
- Bit 18: MANAGE_SERVER - Edit server settings and view audit log
- Bit 19: Reserved (was KICK_MEMBERS)
- Bit 20: BAN_MEMBERS - Ban members
- Bit 21: CREATE_INVITES - Create invite links
- Bit 22: MANAGE_INVITES - Revoke invites created by others
- Bit 23: CHANGE_NICKNAME - Change own display name
- Bit 24: MANAGE_NICKNAMES - Change others' display names
- Bits 25-30: Reserved
- Bit 31: ADMINISTRATOR - Bypass all permission checks (except Owner)

## Voice Permissions (bits 32-47)
- Bit 32: USE_VOICE - Connect to voice channels and transmit audio
- Bit 33: VIDEO - Share video in voice
- Bit 34: MUTE_MEMBERS - Server-mute others
- Bit 35: DEAFEN_MEMBERS - Server-deafen others
- Bit 36: MOVE_MEMBERS - Move members between voice channels
- Bit 37: PRIORITY_SPEAKER - Voice priority (duck others)
- Bits 38-47: Reserved

## Reserved (bits 48-63)
Reserved for future features.

## Resolution Algorithm
1. If user has "owner" role → return all bits (bypass everything)
2. Base = OR all user role permissions
3. If base has ADMINISTRATOR bit → return all bits (bypass channel overrides only)
4. Accumulate channel overrides: `accumulated_allow = OR(all role allows)`, `accumulated_deny = OR(all role denys)`
5. Final: `(base & ~accumulated_deny) | accumulated_allow`

## Default Role Bitmasks
- **Owner**: 0 (bypasses all checks via special role status, not bitmask)
- **Admin**: 272763916159 (ADMINISTRATOR + all channel/server/voice perms incl. SEND_IN_LOCKED_THREADS)
- **Moderator**: 3671935 (channel perms + BAN + CREATE_INVITES)
- **Member**: 10486583 (VIEW_CHANNEL + SEND_MESSAGES + MANAGE_OWN_MESSAGES + UPLOAD_FILES + ADD_REACTIONS + CREATE_THREADS + MANAGE_OWN_THREADS + CREATE_INVITES + CHANGE_NICKNAME)
