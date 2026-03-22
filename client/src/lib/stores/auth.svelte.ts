import { getMe, type User } from '$lib/api/users.js';
import { getSetupStatus } from '$lib/api/auth.js';
import type { Permission } from '$lib/permissions.js';
import { decodePermissions } from '$lib/permissions.js';

export interface UserPermissions {
	server: string[];
	channels: Map<string, string[]>;
}

let user = $state<User | null>(null);
let setupRequired = $state(false);
let insecureMode = $state(false);
let initialized = $state(false);
let passwordMustChange = $state(false);
let permissions = $state<UserPermissions>({ server: [], channels: new Map() });
let permissionsLoaded = $state(false);
let mediaSearchEnabled = $state(false);
let youtubeEmbedEnabled = $state(true);
let serverSettings = $state<{ server_name: string | null; server_icon: string | null; server_description: string | null; maintenance_enabled?: boolean; maintenance_message?: string | null }>({
	server_name: 'Fold',
	server_icon: null,
	server_description: null,
	maintenance_enabled: false,
	maintenance_message: null
});

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

export function isInsecureMode(): boolean {
	return insecureMode;
}

export function getPasswordMustChange(): boolean {
	return passwordMustChange;
}

export function setPasswordMustChange(v: boolean) {
	passwordMustChange = v;
}

export function isInitialized(): boolean {
	return initialized;
}

export async function init() {
	if (initialized) return;

	try {
	const status = await getSetupStatus();
		setupRequired = status.setup_required;
		insecureMode = status.insecure;

		if (!setupRequired) {
			try {
				user = await getMe();
			} catch (err: unknown) {
				user = null;
				if (err && typeof err === 'object' && 'error' in err && (err as { error: string }).error === 'password_must_change') {
					passwordMustChange = true;
				}
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

/** Accept both legacy string[] and new bitmask formats */
export function setPermissions(
	server: string[] | number,
	channels: Record<string, string[] | number>
) {
	const serverPerms = typeof server === 'number' ? decodePermissions(server) : server;
	const channelMap = new Map<string, string[]>();
	for (const [id, val] of Object.entries(channels)) {
		channelMap.set(id, typeof val === 'number' ? decodePermissions(val) : val);
	}
	permissions = { server: serverPerms, channels: channelMap };
	permissionsLoaded = true;
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

export function arePermissionsLoaded(): boolean {
	return permissionsLoaded;
}

export function hasChannelPermission(channelId: string, perm: Permission): boolean {
	const channelPerms = permissions.channels.get(channelId);
	if (channelPerms) return channelPerms.includes(perm);
	// Channel not in map (e.g. newly created) — fall back to server permissions
	return permissions.server.includes(perm);
}

export function getMediaSearchEnabled(): boolean {
	return mediaSearchEnabled;
}

export function setMediaSearchEnabled(v: boolean) {
	mediaSearchEnabled = v;
}

export function getYoutubeEmbedEnabled(): boolean {
	return youtubeEmbedEnabled;
}

export function setYoutubeEmbedEnabled(v: boolean) {
	youtubeEmbedEnabled = v;
}

export function getServerSettings() {
	return serverSettings;
}

export function setServerSettings(s: { server_name?: string | null; server_icon?: string | null; server_description?: string | null; maintenance_enabled?: boolean; maintenance_message?: string | null }) {
	serverSettings = { ...serverSettings, ...s };
}

export function isMaintenanceEnabled(): boolean {
	return serverSettings.maintenance_enabled ?? false;
}

export function getMaintenanceMessage(): string | null {
	return serverSettings.maintenance_message ?? null;
}

export function getServerName(): string {
	return serverSettings.server_name || 'fold';
}

export function reset() {
	user = null;
	initialized = false;
	setupRequired = false;
	insecureMode = false;
	passwordMustChange = false;
	permissions = { server: [], channels: new Map() };
	permissionsLoaded = false;
	mediaSearchEnabled = false;
	youtubeEmbedEnabled = true;
	serverSettings = { server_name: 'Fold', server_icon: null, server_description: null, maintenance_enabled: false, maintenance_message: null };
}
