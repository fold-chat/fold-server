import type { Message } from '$lib/api/messages.js';
import { incrementUnread } from './channels.svelte.js';

// channelId → messages (sorted oldest first)
let messagesByChannel = $state<Map<string, Message[]>>(new Map());
let loadingChannels = $state<Set<string>>(new Set());
let hasMoreByChannel = $state<Map<string, boolean>>(new Map());
// channelId → { userId, username, timestamp }
let typingByChannel = $state<Map<string, Map<string, { username: string; timestamp: number }>>>(new Map());

let activeChannelId = $state<string | null>(null);

export function getActiveChannelId(): string | null {
	return activeChannelId;
}

export function setActiveChannelId(id: string | null) {
	activeChannelId = id;
}

export function getMessages(channelId: string): Message[] {
	return messagesByChannel.get(channelId) ?? [];
}

export function isLoading(channelId: string): boolean {
	return loadingChannels.has(channelId);
}

export function hasMore(channelId: string): boolean {
	return hasMoreByChannel.get(channelId) ?? true;
}

export function setMessages(channelId: string, msgs: Message[]) {
	messagesByChannel = new Map(messagesByChannel);
	// API returns newest first, reverse for chronological display
	messagesByChannel.set(channelId, [...msgs].reverse());
}

export function prependMessages(channelId: string, msgs: Message[]) {
	messagesByChannel = new Map(messagesByChannel);
	const existing = messagesByChannel.get(channelId) ?? [];
	// msgs from API are newest-first, reverse for chronological
	messagesByChannel.set(channelId, [...[...msgs].reverse(), ...existing]);
}

export function appendMessage(channelId: string, msg: Message) {
	messagesByChannel = new Map(messagesByChannel);
	const existing = messagesByChannel.get(channelId) ?? [];
	// Dedup by ID
	if (!existing.find((m) => m.id === msg.id)) {
		messagesByChannel.set(channelId, [...existing, msg]);
	}
}

export function updateMessage(msg: Message) {
	const channelId = msg.channel_id;
	messagesByChannel = new Map(messagesByChannel);
	const existing = messagesByChannel.get(channelId) ?? [];
	messagesByChannel.set(
		channelId,
		existing.map((m) => (m.id === msg.id ? msg : m))
	);
}

export function removeMessage(id: string, channelId: string) {
	messagesByChannel = new Map(messagesByChannel);
	const existing = messagesByChannel.get(channelId) ?? [];
	messagesByChannel.set(
		channelId,
		existing.filter((m) => m.id !== id)
	);
}

export function setLoading(channelId: string, loading: boolean) {
	loadingChannels = new Set(loadingChannels);
	if (loading) loadingChannels.add(channelId);
	else loadingChannels.delete(channelId);
}

export function setHasMore(channelId: string, more: boolean) {
	hasMoreByChannel = new Map(hasMoreByChannel);
	hasMoreByChannel.set(channelId, more);
}

export function getTypingUsers(channelId: string): string[] {
	const typing = typingByChannel.get(channelId);
	if (!typing) return [];
	const now = Date.now();
	const active: string[] = [];
	for (const [, v] of typing) {
		if (now - v.timestamp < 5000) active.push(v.username);
	}
	return active;
}

export function setTyping(channelId: string, userId: string, username: string) {
	typingByChannel = new Map(typingByChannel);
	const existing = typingByChannel.get(channelId) ?? new Map();
	const updated = new Map(existing);
	updated.set(userId, { username, timestamp: Date.now() });
	typingByChannel.set(channelId, updated);
}

export function clearTyping(channelId: string, userId: string) {
	const existing = typingByChannel.get(channelId);
	if (!existing || !existing.has(userId)) return;
	typingByChannel = new Map(typingByChannel);
	const updated = new Map(existing);
	updated.delete(userId);
	typingByChannel.set(channelId, updated);
}

export function handleMessageEvent(op: string, data: Record<string, unknown> | undefined) {
	if (!data) return;
	switch (op) {
		case 'MESSAGE_CREATE': {
			const msg = data as unknown as Message;
			appendMessage(msg.channel_id, msg);
			// Clear typing indicator when user sends a message
			if (msg.author_id) clearTyping(msg.channel_id, msg.author_id as string);
			if (msg.channel_id !== activeChannelId) {
				incrementUnread(msg.channel_id);
			}
			break;
		}
		case 'MESSAGE_UPDATE': {
			const msg = data as unknown as Message;
			updateMessage(msg);
			break;
		}
		case 'MESSAGE_DELETE': {
			const id = data.id as string;
			const channelId = data.channel_id as string;
			if (id && channelId) removeMessage(id, channelId);
			break;
		}
	}
}

export function handleTypingEvent(op: string, data: Record<string, unknown> | undefined) {
	if (!data) return;
	const channelId = data.channel_id as string;
	const userId = data.user_id as string;
	if (!channelId || !userId) return;

	if (op === 'TYPING_STOP') {
		clearTyping(channelId, userId);
	} else {
		const username = data.username as string;
		if (username) setTyping(channelId, userId, username);
	}
}
