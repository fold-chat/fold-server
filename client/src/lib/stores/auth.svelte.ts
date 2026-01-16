import { getMe, type User } from '$lib/api/users.js';
import { getSetupStatus } from '$lib/api/auth.js';
import type { Permission } from '$lib/permissions.js';

export interface UserPermissions {
	server: string[];
	channels: Map<string, string[]>;
}

let user = $state<User | null>(null);
let setupRequired = $state(false);
let initialized = $state(false);
let permissions = $state<UserPermissions>({ server: [], channels: new Map() });

export function getUser(): User | null {
	return user;
}

export function setUser(u: User | null) {
	user = u;
}

export function isAuthenticated(): boolean {
	return user !== null;
}

export function isSetupRequired(): boolean {
	return setupRequired;
}

export function isInitialized(): boolean {
	return initialized;
}

export async function init() {
	if (initialized) return;

	try {
		const status = await getSetupStatus();
		setupRequired = status.setup_required;

		if (!setupRequired) {
			try {
				user = await getMe();
			} catch {
				user = null;
			}
		}
	} catch {
		// Server unreachable
	}

	initialized = true;
}

export function getPermissions(): UserPermissions {
	return permissions;
}

export function setPermissions(server: string[], channels: Record<string, string[]>) {
	permissions = {
		server,
		channels: new Map(Object.entries(channels))
	};
}

export function updateChannelPermissions(channelId: string, perms: string[]) {
	permissions = {
		server: permissions.server,
		channels: new Map(permissions.channels)
	};
	permissions.channels.set(channelId, perms);
}

export function hasServerPermission(perm: Permission): boolean {
	return permissions.server.includes(perm);
}

export function hasChannelPermission(channelId: string, perm: Permission): boolean {
	return permissions.channels.get(channelId)?.includes(perm) ?? false;
}

export function reset() {
	user = null;
	initialized = false;
	setupRequired = false;
	permissions = { server: [], channels: new Map() };
}
