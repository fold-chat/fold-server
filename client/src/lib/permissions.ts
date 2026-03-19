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
	BAN_MEMBERS: 'BAN_MEMBERS',
	CREATE_INVITES: 'CREATE_INVITES',
	MANAGE_INVITES: 'MANAGE_INVITES',
	CHANGE_NICKNAME: 'CHANGE_NICKNAME',
	MANAGE_NICKNAMES: 'MANAGE_NICKNAMES',
	RESET_PASSWORDS: 'RESET_PASSWORDS',
	INITIATE_DM: 'INITIATE_DM',
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
	PermissionName.BAN_MEMBERS,
	PermissionName.CREATE_INVITES,
	PermissionName.MANAGE_INVITES,
	PermissionName.CHANGE_NICKNAME,
	PermissionName.MANAGE_NICKNAMES,
	PermissionName.RESET_PASSWORDS,
	PermissionName.INITIATE_DM,
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
			name: PermissionName.RESET_PASSWORDS,
			label: 'Reset Passwords',
			desc: "Reset other members' passwords"
		},
		{
			name: PermissionName.ADMINISTRATOR,
			label: 'Administrator',
			desc: 'Bypass all permission checks (except Owner)'
		}
	],
	'Text Channel': [
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

// Dependency hierarchy: each perm maps to its direct prerequisites
export const PERMISSION_DEPS: Record<string, string[]> = {
	// Text Channel
	SEND_MESSAGES: ['VIEW_CHANNEL'],
	MANAGE_OWN_MESSAGES: ['SEND_MESSAGES'],
	MANAGE_MESSAGES: ['VIEW_CHANNEL'],
	UPLOAD_FILES: ['SEND_MESSAGES'],
	ADD_REACTIONS: ['VIEW_CHANNEL'],
	MENTION_EVERYONE: ['SEND_MESSAGES'],
	CREATE_THREADS: ['SEND_MESSAGES'],
	MANAGE_OWN_THREADS: ['CREATE_THREADS'],
	MANAGE_THREADS: ['VIEW_CHANNEL'],
	SEND_IN_LOCKED_THREADS: ['SEND_MESSAGES'],
	// Server
	MANAGE_INVITES: ['CREATE_INVITES'],
	// Voice
	VIDEO: ['USE_VOICE'],
	MUTE_MEMBERS: ['USE_VOICE'],
	DEAFEN_MEMBERS: ['USE_VOICE'],
	MOVE_MEMBERS: ['USE_VOICE'],
	PRIORITY_SPEAKER: ['USE_VOICE']
};

/** All transitive prerequisites of a permission */
export function getAllPrerequisites(perm: string): Set<string> {
	const result = new Set<string>();
	const stack = [...(PERMISSION_DEPS[perm] || [])];
	while (stack.length) {
		const dep = stack.pop()!;
		if (!result.has(dep)) {
			result.add(dep);
			stack.push(...(PERMISSION_DEPS[dep] || []));
		}
	}
	return result;
}

/** All perms that transitively depend on this perm */
export function getAllDependents(perm: string): Set<string> {
	const result = new Set<string>();
	const stack = [perm];
	while (stack.length) {
		const current = stack.pop()!;
		for (const [p, deps] of Object.entries(PERMISSION_DEPS)) {
			if (deps.includes(current) && !result.has(p)) {
				result.add(p);
				stack.push(p);
			}
		}
	}
	return result;
}

/** Given enabled perms, return the subset that are forced on as prerequisites of other enabled perms */
export function getForcedPrereqs(enabled: Set<string>): Set<string> {
	const forced = new Set<string>();
	for (const perm of enabled) {
		for (const prereq of getAllPrerequisites(perm)) {
			forced.add(prereq);
		}
	}
	return forced;
}
