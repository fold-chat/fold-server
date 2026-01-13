import type { Channel, Category } from '$lib/api/channels.js';
import type { Member } from '$lib/api/users.js';
import { setChannels, setCategories, addChannel, updateChannel, removeChannel, addCategory, updateCategory, removeCategory, setReadStates } from './channels.svelte.js';
import { handleMessageEvent, handleTypingEvent } from './messages.svelte.js';

type ConnectionState = 'connecting' | 'connected' | 'disconnected' | 'reconnecting';

let ws = $state<WebSocket | null>(null);
let connectionState = $state<ConnectionState>('disconnected');
let heartbeatInterval = $state<ReturnType<typeof setInterval> | null>(null);
let reconnectTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
let reconnectAttempts = $state(0);
let sessionId = $state<string | null>(null);

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
		scheduleReconnect();
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
			handleHello(msg.d as HelloPayload);
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
			if (msg.d?.id) removeChannel(msg.d.id as string);
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
	}
}

interface HelloPayload {
	user: Record<string, unknown>;
	channels: Channel[];
	categories: Category[];
	members: Member[];
	read_states: Array<{ channel_id: string; last_read_message_id: string | null }>;
	heartbeat_interval_ms: number;
	session_id: string;
}

function handleHello(data: HelloPayload) {
	sessionId = data.session_id;
	setChannels(data.channels ?? []);
	setCategories(data.categories ?? []);
	setReadStates(data.read_states ?? []);
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
