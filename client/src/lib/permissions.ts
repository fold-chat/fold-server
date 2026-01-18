// Permission name constants — type safety without bitmasks
export const PermissionName = {
	// Channel (bits 0-15)
	VIEW_CHANNEL: 'VIEW_CHANNEL',
	SEND_MESSAGES: 'SEND_MESSAGES',
	MANAGE_OWN_MESSAGES: 'MANAGE_OWN_MESSAGES',
	MANAGE_MESSAGES: 'MANAGE_MESSAGES',
	UPLOAD_FILES: 'UPLOAD_FILES',
	ADD_REACTIONS: 'ADD_REACTIONS',
	MENTION_EVERYONE: 'MENTION_EVERYONE',
	CREATE_THREADS: 'CREATE_THREADS',
	MANAGE_OWN_THREADS: 'MANAGE_OWN_THREADS',
	MANAGE_THREADS: 'MANAGE_THREADS',
	SEND_IN_LOCKED_THREADS: 'SEND_IN_LOCKED_THREADS',
	// Server (bits 16-31)
	MANAGE_CHANNELS: 'MANAGE_CHANNELS',
	MANAGE_ROLES: 'MANAGE_ROLES',
	MANAGE_SERVER: 'MANAGE_SERVER',
	KICK_MEMBERS: 'KICK_MEMBERS',
	BAN_MEMBERS: 'BAN_MEMBERS',
	CREATE_INVITES: 'CREATE_INVITES',
	MANAGE_INVITES: 'MANAGE_INVITES',
	CHANGE_NICKNAME: 'CHANGE_NICKNAME',
	MANAGE_NICKNAMES: 'MANAGE_NICKNAMES',
	ADMINISTRATOR: 'ADMINISTRATOR',
	// Voice (bits 32-47)
	USE_VOICE: 'USE_VOICE',
	VIDEO: 'VIDEO',
	MUTE_MEMBERS: 'MUTE_MEMBERS',
	DEAFEN_MEMBERS: 'DEAFEN_MEMBERS',
	MOVE_MEMBERS: 'MOVE_MEMBERS',
	PRIORITY_SPEAKER: 'PRIORITY_SPEAKER'
} as const;

export type Permission = (typeof PermissionName)[keyof typeof PermissionName];

// Server-level permissions (hidden from channel override UI)
const SERVER_LEVEL = new Set<string>([
	PermissionName.MANAGE_CHANNELS,
	PermissionName.MANAGE_ROLES,
	PermissionName.MANAGE_SERVER,
	PermissionName.KICK_MEMBERS,
	PermissionName.BAN_MEMBERS,
	PermissionName.CREATE_INVITES,
	PermissionName.MANAGE_INVITES,
	PermissionName.CHANGE_NICKNAME,
	PermissionName.MANAGE_NICKNAMES,
	PermissionName.ADMINISTRATOR
]);

export function isServerLevelPermission(perm: string): boolean {
	return SERVER_LEVEL.has(perm);
}

export function hasPermission(permissions: string[], perm: Permission): boolean {
	return permissions.includes(perm);
}

// Grouped for UI display
export const PERMISSION_GROUPS = {
	Channel: [
		{ name: PermissionName.VIEW_CHANNEL, label: 'View Channel', desc: 'View channel and read messages' },
		{ name: PermissionName.SEND_MESSAGES, label: 'Send Messages', desc: 'Send messages in channel' },
		{
			name: PermissionName.MANAGE_OWN_MESSAGES,
			label: 'Manage Own Messages',
			desc: 'Edit/delete own messages'
		},
		{
			name: PermissionName.MANAGE_MESSAGES,
			label: 'Manage Messages',
			desc: 'Edit/delete/pin any message'
		},
		{ name: PermissionName.UPLOAD_FILES, label: 'Upload Files', desc: 'Upload files/attachments' },
		{ name: PermissionName.ADD_REACTIONS, label: 'Add Reactions', desc: 'Add emoji reactions' },
		{
			name: PermissionName.MENTION_EVERYONE,
			label: 'Mention Everyone',
			desc: 'Use @everyone/@here mentions'
		},
		{ name: PermissionName.CREATE_THREADS, label: 'Create Threads', desc: 'Create threads from messages' },
		{
			name: PermissionName.MANAGE_OWN_THREADS,
			label: 'Manage Own Threads',
			desc: 'Lock/archive/delete own threads'
		},
		{ name: PermissionName.MANAGE_THREADS, label: 'Manage Threads', desc: 'Lock/archive/delete any thread' },
		{ name: PermissionName.SEND_IN_LOCKED_THREADS, label: 'Send in Locked Threads', desc: 'Post replies in locked threads' }
	],
	Server: [
		{
			name: PermissionName.MANAGE_CHANNELS,
			label: 'Manage Channels',
			desc: 'Create/edit/delete channels and categories'
		},
		{
			name: PermissionName.MANAGE_ROLES,
			label: 'Manage Roles',
			desc: 'Create/edit/delete roles, assign roles'
		},
		{
			name: PermissionName.MANAGE_SERVER,
			label: 'Manage Server',
			desc: 'Edit server settings'
		},
		{ name: PermissionName.KICK_MEMBERS, label: 'Kick Members', desc: 'Remove members temporarily' },
		{ name: PermissionName.BAN_MEMBERS, label: 'Ban Members', desc: 'Permanently ban members' },
		{ name: PermissionName.CREATE_INVITES, label: 'Create Invites', desc: 'Create invite links' },
		{
			name: PermissionName.MANAGE_INVITES,
			label: 'Manage Invites',
			desc: "Revoke others' invites"
		},
		{
			name: PermissionName.CHANGE_NICKNAME,
			label: 'Change Nickname',
			desc: 'Change own display name'
		},
		{
			name: PermissionName.MANAGE_NICKNAMES,
			label: 'Manage Nicknames',
			desc: "Change others' display names"
		},
		{
			name: PermissionName.ADMINISTRATOR,
			label: 'Administrator',
			desc: 'Bypass all permission checks (except Owner)'
		}
	],
	Voice: [
		{ name: PermissionName.USE_VOICE, label: 'Use Voice', desc: 'Connect to voice channels' },
		{ name: PermissionName.VIDEO, label: 'Video', desc: 'Share video in voice' },
		{ name: PermissionName.MUTE_MEMBERS, label: 'Mute Members', desc: 'Server-mute others' },
		{ name: PermissionName.DEAFEN_MEMBERS, label: 'Deafen Members', desc: 'Server-deafen others' },
		{
			name: PermissionName.MOVE_MEMBERS,
			label: 'Move Members',
			desc: 'Move members between voice channels'
		},
		{
			name: PermissionName.PRIORITY_SPEAKER,
			label: 'Priority Speaker',
			desc: 'Voice priority (duck others)'
		}
	]
} as const;
