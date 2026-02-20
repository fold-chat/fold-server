import type { Channel, Category } from '$lib/api/channels.js';
import type { Member, RoleBadge } from '$lib/api/users.js';
import type { Role } from '$lib/api/roles.js';
import type { ThreadReadState } from '$lib/api/threads.js';
import { setChannels, getChannels, setCategories, addChannel, updateChannel, removeChannel, addCategory, updateCategory, removeCategory, setReadStates, setUnreadCounts, markChannelRead } from './channels.svelte.js';
import { handleMessageEvent, handleTypingEvent, handleReactionEvent } from './messages.svelte.js';
import { handleThreadEvent, setThreadReadStates } from './threads.svelte.js';
import { setRoles, addRole, updateRole as updateStoreRole, removeRole as removeStoreRole } from './roles.svelte.js';
import { setMembers, addMember, removeMember, updateMember, getMembers as getStoreMembers, updateMemberRoleBadge } from './members.svelte.js';
import { getUser, setPermissions, setMediaSearchEnabled, setYoutubeEmbedEnabled, setServerSettings } from './auth.svelte.js';
import { hydrateVoiceStates, setVoiceVideoEnabled, setE2eeCapability, handleVoiceStateUpdate, handleVoiceMove, handleVoiceKeyRotate } from './voice.svelte.js';
import { setOnlineUserIds, setUserOnline, setUserOffline } from './presence.svelte.js';
import { setCustomEmoji, addCustomEmoji, removeCustomEmoji } from './emoji.svelte.js';
import type { CustomEmoji } from '$lib/api/emoji.js';
import { goto } from '$app/navigation';

type ConnectionState = 'connecting' | 'connected' | 'disconnected' | 'reconnecting';

/** Roles arrive as JSON string from DB; parse to array */
function parseRoleBadges(raw: unknown): RoleBadge[] {
	if (Array.isArray(raw)) return raw.filter(r => r && r.id != null);
	if (typeof raw === 'string') {
		try {
			const parsed = JSON.parse(raw);
			return Array.isArray(parsed) ? parsed.filter((r: RoleBadge) => r.id != null) : [];
		} catch {
			return [];
		}
	}
	return [];
}

function normalizeMember(m: Member): Member {
	return { ...m, roles: parseRoleBadges(m.roles) };
}

let ws = $state<WebSocket | null>(null);
let connectionState = $state<ConnectionState>('disconnected');
let heartbeatInterval = $state<ReturnType<typeof setInterval> | null>(null);
let reconnectTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
let reconnectAttempts = $state(0);
let sessionId = $state<string | null>(null);
let authFailed = $state(false);

const MAX_RECONNECT_DELAY = 30_000;

export function getConnectionState(): ConnectionState {
	return connectionState;
}

export function connect() {
	if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return;

	connectionState = 'connecting';
	const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
	const url = `${protocol}//${window.location.host}/api/ws`;

	try {
		ws = new WebSocket(url);
	} catch {
		connectionState = 'disconnected';
		scheduleReconnect();
		return;
	}

	ws.onopen = () => {
		connectionState = 'connected';
		reconnectAttempts = 0;
	};

	ws.onmessage = (event) => {
		try {
			const msg = JSON.parse(event.data);
			handleEvent(msg);
		} catch {
			// ignore parse errors
		}
	};

	ws.onclose = () => {
		connectionState = 'disconnected';
		stopHeartbeat();
		if (authFailed) {
			authFailed = false;
			tryRefreshAndReconnect();
		} else {
			scheduleReconnect();
		}
	};

	ws.onerror = () => {
		// onclose will fire after this
	};
}

export function disconnect() {
	if (reconnectTimeout) {
		clearTimeout(reconnectTimeout);
		reconnectTimeout = null;
	}
	stopHeartbeat();
	if (ws) {
		ws.onclose = null;
		ws.close();
		ws = null;
	}
	connectionState = 'disconnected';
	reconnectAttempts = 0;
}

export function send(op: string, data?: Record<string, unknown>) {
	if (ws && ws.readyState === WebSocket.OPEN) {
		ws.send(JSON.stringify({ op, d: data }));
	}
}

function handleEvent(msg: { op: string; d?: Record<string, unknown>; s?: number }) {
	switch (msg.op) {
		case 'HELLO':
			if (msg.d) handleHello(msg.d as unknown as HelloPayload);
			break;
		case 'AUTH_FAILED':
			authFailed = true;
			break;
		case 'HEARTBEAT_ACK':
			break;
		case 'MESSAGE_CREATE':
		case 'MESSAGE_UPDATE':
		case 'MESSAGE_DELETE':
			handleMessageEvent(msg.op, msg.d);
			break;
		case 'CHANNEL_CREATE':
			if (msg.d) addChannel(msg.d as unknown as Channel);
			break;
		case 'CHANNEL_UPDATE':
			if (msg.d) updateChannel(msg.d as unknown as Channel);
			break;
		case 'CHANNEL_DELETE':
			if (msg.d?.id) {
				const deletedId = msg.d.id as string;
				removeChannel(deletedId);
				const match = window.location.pathname.match(/^\/channels\/([^/]+)/);
				if (match && match[1] === deletedId) goto('/');
			}
			break;
		case 'CATEGORY_CREATE':
			if (msg.d) addCategory(msg.d as unknown as Category);
			break;
		case 'CATEGORY_UPDATE':
			if (msg.d) updateCategory(msg.d as unknown as Category);
			break;
		case 'CATEGORY_DELETE':
			if (msg.d?.id) removeCategory(msg.d.id as string);
			break;
		case 'TYPING_START':
		case 'TYPING_STOP':
			handleTypingEvent(msg.op, msg.d);
			break;
		case 'THREAD_CREATE':
		case 'THREAD_UPDATE':
		case 'THREAD_DELETE':
			handleThreadEvent(msg.op, msg.d);
			break;
		case 'ROLE_CREATE':
			if (msg.d) addRole(msg.d as unknown as Role);
			break;
		case 'ROLE_UPDATE':
			if (msg.d) {
				const updatedRole = msg.d as unknown as Role;
				updateStoreRole(updatedRole);
				updateMemberRoleBadge(updatedRole.id, updatedRole.name, updatedRole.color);
			}
			break;
		case 'ROLE_DELETE':
			if (msg.d?.id) removeStoreRole(msg.d.id as string);
			break;
		case 'MEMBER_ROLE_ASSIGNED':
		case 'MEMBER_ROLE_REMOVED':
			if (msg.d) handleMemberRoleUpdate(msg.op, msg.d);
			break;
		case 'CHANNEL_PERMISSIONS_UPDATE':
			if (msg.d) handleChannelPermissionsUpdate(msg.d);
			break;
		case 'REACTION_ADD':
		case 'REACTION_REMOVE':
			handleReactionEvent(msg.op, msg.d);
			break;
		case 'MEMBER_JOIN':
			if (msg.d) addMember(normalizeMember(msg.d as unknown as Member));
			break;
		case 'MEMBER_BAN':
			if (msg.d?.user_id) {
				const userId = msg.d.user_id as string;
				const me = getUser();
				if (me && me.id === userId) {
					disconnect();
					window.location.href = '/login?reason=banned';
				} else {
					// Update member in store to show as banned
					const members = getStoreMembers();
					const member = members.find(m => m.id === userId);
					if (member) {
						updateMember({ ...member, banned_at: new Date().toISOString(), banned_by: msg.d.banned_by as string ?? null, ban_reason: msg.d.reason as string ?? null, banned_by_username: null });
					}
				}
			}
			break;
	case 'MEMBER_UNBAN':
			if (msg.d?.user_id) {
				const unbannedId = msg.d.user_id as string;
				const existing = getStoreMembers().find(m => m.id === unbannedId);
				if (existing) {
					updateMember({ ...existing, banned_at: null, banned_by: null, ban_reason: null, banned_by_username: null });
				}
			}
			break;
		case 'SERVER_SETTINGS_UPDATE':
			if (msg.d) setServerSettings(msg.d as Record<string, string | null>);
			break;
		case 'READ_STATE_UPDATE':
			if (msg.d?.channel_id && msg.d?.last_read_message_id) {
				markChannelRead(msg.d.channel_id as string, msg.d.last_read_message_id as string);
			}
			break;
		case 'VOICE_STATE_UPDATE':
			if (msg.d) handleVoiceStateUpdate(msg.d);
			break;
		case 'VOICE_MOVE':
			if (msg.d) handleVoiceMove(msg.d);
			break;
		case 'VOICE_KEY_ROTATE':
			if (msg.d) handleVoiceKeyRotate(msg.d);
			break;
		case 'PRESENCE_UPDATE':
			if (msg.d) handlePresenceUpdate(msg.d);
			break;
		case 'EMOJI_CREATE':
			if (msg.d) addCustomEmoji(msg.d as unknown as CustomEmoji);
			break;
		case 'EMOJI_DELETE':
			if (msg.d?.id) removeCustomEmoji(msg.d.id as string);
			break;
	}
}

interface HelloPayload {
	user: Record<string, unknown>;
	channels: Channel[];
	categories: Category[];
	members: Member[];
	roles: Role[];
	read_states: Array<{ channel_id: string; last_read_message_id: string | null }>;
	unread_counts: Array<{ channel_id: string; unread_count: number }>;
	thread_read_states: ThreadReadState[];
	user_permissions: { server: string[]; channels: Record<string, string[]> };
	online_user_ids?: string[];
	heartbeat_interval_ms: number;
	session_id: string;
	media_search?: boolean;
	youtube_embed?: boolean;
	server_settings?: { server_name?: string | null; server_icon?: string | null; server_description?: string | null };
	voice_states?: Array<import('$lib/api/voice.js').VoiceState> | Record<string, Array<import('$lib/api/voice.js').VoiceState>>;
	capabilities?: { voice_video?: boolean; e2ee?: boolean };
	custom_emoji?: CustomEmoji[];
}

function handleUserStateUpdate(data: Record<string, unknown>) {
	if (data.user_permissions) {
		const perms = data.user_permissions as { server: string[]; channels: Record<string, string[]> };
		setPermissions(perms.server ?? [], perms.channels ?? {});
	}
	if (data.channels) {
		const newChannels = data.channels as Channel[];
		const match = window.location.pathname.match(/^\/channels\/([^/]+)/);
		if (match && !newChannels.some((c) => c.id === match[1])) {
			goto('/');
		}
		setChannels(newChannels);
	}
	if (data.categories) {
		setCategories(data.categories as Category[]);
	}
}

function handleMemberRoleUpdate(op: string, data: Record<string, unknown>) {
	const userId = data.user_id as string;
	if (!userId) return;

	// Update member's role badges in the members store for ALL users
	const existing = getStoreMembers().find(m => m.id === userId);
	if (existing) {
		const role = data.role as { id: string; name: string; color: string | null } | undefined;
		const roleId = data.role_id as string;
		if (op === 'MEMBER_ROLE_ASSIGNED' && role) {
			const badge = { id: role.id, name: role.name, color: role.color };
			if (!existing.roles.some(r => r.id === badge.id)) {
				updateMember({ ...existing, roles: [...existing.roles, badge] });
			}
		} else if (op === 'MEMBER_ROLE_REMOVED' && roleId) {
			updateMember({ ...existing, roles: existing.roles.filter(r => r.id !== roleId) });
		}
	}

	// For the current user, also update permissions/channels/categories
	const currentUser = getUser();
	if (currentUser && userId === currentUser.id) {
		handleUserStateUpdate(data);
	}
}

function handleChannelPermissionsUpdate(data: Record<string, unknown>) {
	const currentUser = getUser();
	if (currentUser && data.user_id === currentUser.id) {
		handleUserStateUpdate(data);
	}
}

function handlePresenceUpdate(data: Record<string, unknown>) {
	const userId = data.user_id as string;
	const status = data.status as string;
	if (!userId) return;
	if (status === 'online') {
		setUserOnline(userId);
	} else if (status === 'offline') {
		setUserOffline(userId);
		// Update last_seen_at on the member in store
		const lastSeen = data.last_seen_at as string | null;
		if (lastSeen) {
			const existing = getStoreMembers().find(m => m.id === userId);
			if (existing) updateMember({ ...existing, last_seen_at: lastSeen });
		}
	}
}

function handleHello(data: HelloPayload) {
	sessionId = data.session_id;
	setChannels(data.channels ?? []);
	setCategories(data.categories ?? []);
	setReadStates(data.read_states ?? []);
	setUnreadCounts(data.unread_counts ?? []);
	setThreadReadStates(data.thread_read_states ?? []);
	if (data.roles) setRoles(data.roles);
	if (data.members) setMembers(data.members.map(normalizeMember));
	if (data.user_permissions) {
		setPermissions(
			data.user_permissions.server ?? [],
			data.user_permissions.channels ?? {}
		);
	}
	setMediaSearchEnabled(data.media_search ?? false);
	setYoutubeEmbedEnabled(data.youtube_embed ?? true);
	if (data.server_settings) setServerSettings(data.server_settings);
	if (data.online_user_ids) setOnlineUserIds(data.online_user_ids);
	setCustomEmoji(data.custom_emoji ?? []);
	// Server sends voice_states as { channelId: VoiceState[] } — flatten to array
	const rawVs = data.voice_states;
	let voiceArr: import('$lib/api/voice.js').VoiceState[] = [];
	if (Array.isArray(rawVs)) {
		voiceArr = rawVs;
	} else if (rawVs && typeof rawVs === 'object') {
		for (const states of Object.values(rawVs as Record<string, import('$lib/api/voice.js').VoiceState[]>)) {
			voiceArr.push(...states);
		}
	}
	hydrateVoiceStates(voiceArr);
	setVoiceVideoEnabled(data.capabilities?.voice_video ?? false);
	setE2eeCapability(data.capabilities?.e2ee ?? false);
	startHeartbeat(data.heartbeat_interval_ms || 30000);
}

function startHeartbeat(intervalMs: number) {
	stopHeartbeat();
	heartbeatInterval = setInterval(() => send('HEARTBEAT'), intervalMs);
}

function stopHeartbeat() {
	if (heartbeatInterval) {
		clearInterval(heartbeatInterval);
		heartbeatInterval = null;
	}
}

async function tryRefreshAndReconnect() {
	try {
		const res = await fetch('/api/v0/auth/refresh', {
			method: 'POST',
			credentials: 'same-origin',
			headers: { 'Content-Type': 'application/json' }
		});
		if (res.ok) {
			reconnectAttempts = 0;
			connect();
			return;
		}
	} catch {
		// refresh failed
	}
	if (typeof window !== 'undefined') {
		window.location.href = '/login';
	}
}

function scheduleReconnect() {
	if (reconnectTimeout) return;
	connectionState = 'reconnecting';
	const delay = Math.min(1000 * Math.pow(2, reconnectAttempts), MAX_RECONNECT_DELAY);
	reconnectAttempts++;
	reconnectTimeout = setTimeout(() => {
		reconnectTimeout = null;
		connect();
	}, delay);
}
