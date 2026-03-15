import type { Thread, ThreadReadState } from '$lib/api/threads.js';
import type { Message, ReactionGroup } from '$lib/api/messages.js';
import { getUser } from './auth.svelte.js';

// Active thread panel
let activeThread = $state<Thread | null>(null);

// Pending thread (panel open, not yet created on server)
export interface PendingThread {
	parent_message_id: string;
	channel_id: string;
}
let pendingThread = $state<PendingThread | null>(null);

// threadId → messages (oldest first)
let messagesByThread = $state<Map<string, Message[]>>(new Map());
let loadingThreads = $state<Set<string>>(new Set());
let hasMoreByThread = $state<Map<string, boolean>>(new Map());

// channelId → threads (most recent activity first)
let threadsByChannel = $state<Map<string, Thread[]>>(new Map());

// threadId → { userId: { username, timestamp } }
let typingByThread = $state<Map<string, Map<string, { username: string; timestamp: number }>>>(new Map());

// threadId → read state
let threadReadStates = $state<Map<string, ThreadReadState>>(new Map());

// --- Active thread ---

export function getActiveThread(): Thread | null {
	return activeThread;
}

export function setActiveThread(thread: Thread | null) {
	activeThread = thread;
	pendingThread = null;
}

export function getPendingThread(): PendingThread | null {
	return pendingThread;
}

export function setPendingThread(pending: PendingThread | null) {
	pendingThread = pending;
	if (pending) activeThread = null;
}

export function closeThreadPanel() {
	activeThread = null;
	pendingThread = null;
}

// --- Thread messages ---

export function getThreadMessages(threadId: string): Message[] {
	return messagesByThread.get(threadId) ?? [];
}

export function isThreadLoading(threadId: string): boolean {
	return loadingThreads.has(threadId);
}

export function hasMoreThreadMessages(threadId: string): boolean {
	return hasMoreByThread.get(threadId) ?? true;
}

export function setThreadMessages(threadId: string, msgs: Message[]) {
	messagesByThread = new Map(messagesByThread);
	messagesByThread.set(threadId, [...msgs].reverse());
}

export function prependThreadMessages(threadId: string, msgs: Message[]) {
	messagesByThread = new Map(messagesByThread);
	const existing = messagesByThread.get(threadId) ?? [];
	messagesByThread.set(threadId, [...[...msgs].reverse(), ...existing]);
}

export function appendThreadMessage(threadId: string, msg: Message) {
	messagesByThread = new Map(messagesByThread);
	const existing = messagesByThread.get(threadId) ?? [];
	if (!existing.find((m) => m.id === msg.id)) {
		messagesByThread.set(threadId, [...existing, msg]);
	}
}

export function updateThreadMessage(msg: Message) {
	if (!msg.thread_id) return;
	messagesByThread = new Map(messagesByThread);
	const existing = messagesByThread.get(msg.thread_id) ?? [];
	messagesByThread.set(
		msg.thread_id,
		existing.map((m) => (m.id === msg.id ? msg : m))
	);
}

export function removeThreadMessage(id: string, threadId: string) {
	messagesByThread = new Map(messagesByThread);
	const existing = messagesByThread.get(threadId) ?? [];
	messagesByThread.set(
		threadId,
		existing.filter((m) => m.id !== id)
	);
}

export function setThreadLoading(threadId: string, loading: boolean) {
	loadingThreads = new Set(loadingThreads);
	if (loading) loadingThreads.add(threadId);
	else loadingThreads.delete(threadId);
}

export function setThreadHasMore(threadId: string, more: boolean) {
	hasMoreByThread = new Map(hasMoreByThread);
	hasMoreByThread.set(threadId, more);
}

// --- Thread lists per channel ---

export function getChannelThreads(channelId: string): Thread[] {
	return threadsByChannel.get(channelId) ?? [];
}

export function setChannelThreads(channelId: string, threads: Thread[]) {
	threadsByChannel = new Map(threadsByChannel);
	threadsByChannel.set(channelId, threads);
}

export function addChannelThread(thread: Thread) {
	threadsByChannel = new Map(threadsByChannel);
	const existing = threadsByChannel.get(thread.channel_id) ?? [];
	if (!existing.find((t) => t.id === thread.id)) {
		threadsByChannel.set(thread.channel_id, [thread, ...existing]);
	}
}

export function updateChannelThread(thread: Thread) {
	threadsByChannel = new Map(threadsByChannel);
	const existing = threadsByChannel.get(thread.channel_id) ?? [];
	const updated = existing.map((t) => (t.id === thread.id ? thread : t));
	// Re-sort: pinned first, then by last_activity_at desc
	updated.sort((a, b) => {
		if ((b.pinned ?? 0) !== (a.pinned ?? 0)) return (b.pinned ?? 0) - (a.pinned ?? 0);
		return (b.last_activity_at ?? '').localeCompare(a.last_activity_at ?? '');
	});
	threadsByChannel.set(thread.channel_id, updated);
	// Also update active thread if it's the one being updated
	if (activeThread && activeThread.id === thread.id) {
		activeThread = thread;
	}
}

export function removeChannelThread(threadId: string, channelId: string) {
	threadsByChannel = new Map(threadsByChannel);
	const existing = threadsByChannel.get(channelId) ?? [];
	threadsByChannel.set(
		channelId,
		existing.filter((t) => t.id !== threadId)
	);
	// Close panel if viewing deleted thread
	if (activeThread && activeThread.id === threadId) {
		activeThread = null;
	}
	// Clean up messages
	messagesByThread = new Map(messagesByThread);
	messagesByThread.delete(threadId);
}

/** Find thread by parent_message_id across all channels */
export function findThreadByParentMessage(messageId: string): Thread | undefined {
	for (const threads of threadsByChannel.values()) {
		const found = threads.find((t) => t.parent_message_id === messageId);
		if (found) return found;
	}
	return undefined;
}

// --- Typing ---

export function getThreadTypingUsers(threadId: string): string[] {
	const typing = typingByThread.get(threadId);
	if (!typing) return [];
	const now = Date.now();
	const active: string[] = [];
	for (const [, v] of typing) {
		if (now - v.timestamp < 5000) active.push(v.username);
	}
	return active;
}

export function setThreadTyping(threadId: string, userId: string, username: string) {
	typingByThread = new Map(typingByThread);
	const existing = typingByThread.get(threadId);
	const updated = existing ? new Map(existing) : new Map<string, { username: string; timestamp: number }>();
	updated.set(userId, { username, timestamp: Date.now() });
	typingByThread.set(threadId, updated);
}

export function clearThreadTyping(threadId: string, userId: string) {
	const existing = typingByThread.get(threadId);
	if (!existing || !existing.has(userId)) return;
	typingByThread = new Map(typingByThread);
	const updated = new Map(existing);
	updated.delete(userId);
	typingByThread.set(threadId, updated);
}

// --- Read states ---

export function getThreadReadStates(): Map<string, ThreadReadState> {
	return threadReadStates;
}

export function getThreadReadState(threadId: string): ThreadReadState | undefined {
	return threadReadStates.get(threadId);
}

export function setThreadReadStates(states: ThreadReadState[]) {
	threadReadStates = new Map(states.map((s) => [s.thread_id, s]));
}

export function markThreadRead(threadId: string, messageId: string) {
	threadReadStates = new Map(threadReadStates);
	const existing = threadReadStates.get(threadId);
	if (existing) {
		threadReadStates.set(threadId, { ...existing, last_read_message_id: messageId, unread_count: 0 });
	} else {
		threadReadStates.delete(threadId);
	}
}

export function getThreadUnreadCount(threadId: string): number {
	return threadReadStates.get(threadId)?.unread_count ?? 0;
}

export function getUnreadThreadCountForChannel(channelId: string): number {
	let count = 0;
	for (const rs of threadReadStates.values()) {
		if (rs.channel_id === channelId && rs.unread_count > 0) count++;
	}
	return count;
}

// --- WS event handlers ---

export function handleThreadEvent(op: string, data: Record<string, unknown> | undefined) {
	if (!data) return;
	switch (op) {
		case 'THREAD_CREATE': {
			const thread = data as unknown as Thread;
			addChannelThread(thread);
			break;
		}
		case 'THREAD_UPDATE': {
			const thread = data as unknown as Thread;
			updateChannelThread(thread);
			break;
		}
		case 'THREAD_DELETE': {
			const id = data.id as string;
			const channelId = data.channel_id as string;
			if (id && channelId) removeChannelThread(id, channelId);
			break;
		}
	}
}

/**
 * Handle message events that belong to threads.
 * Returns true if the message was handled (has thread_id), false otherwise.
 */
export function handleThreadMessageEvent(op: string, data: Record<string, unknown> | undefined): boolean {
	if (!data) return false;
	const threadId = data.thread_id as string | null;
	if (!threadId) return false;

	switch (op) {
		case 'MESSAGE_CREATE': {
			const msg = data as unknown as Message;
			appendThreadMessage(threadId, msg);
			if (msg.author_id) clearThreadTyping(threadId, msg.author_id);
			// Bump reply_count on the thread in channel list
			bumpThreadActivity(threadId, msg.channel_id);
			break;
		}
		case 'MESSAGE_UPDATE': {
			const msg = data as unknown as Message;
			updateThreadMessage(msg);
			break;
		}
		case 'MESSAGE_DELETE': {
			const id = data.id as string;
			if (id && threadId) removeThreadMessage(id, threadId);
			break;
		}
	}
	return true;
}

export function handleThreadReactionEvent(op: string, messageId: string, userId: string, emoji: string) {
	// Search all threads for this message
	for (const [threadId, msgs] of messagesByThread) {
		const msgIdx = msgs.findIndex((m) => m.id === messageId);
		if (msgIdx === -1) continue;

		const msg = msgs[msgIdx];
		const reactions: ReactionGroup[] = [...(msg.reactions ?? [])];
		const groupIdx = reactions.findIndex((r) => r.emoji === emoji);
		const me = getUser()?.id === userId;

		if (op === 'REACTION_ADD') {
			if (groupIdx >= 0) {
				const group = reactions[groupIdx];
				if (!group.users.includes(userId)) {
					reactions[groupIdx] = { ...group, count: group.count + 1, users: [...group.users, userId], me: group.me || me };
				}
			} else {
				reactions.push({ emoji, count: 1, users: [userId], me });
			}
		} else if (op === 'REACTION_REMOVE') {
			if (groupIdx >= 0) {
				const group = reactions[groupIdx];
				const newUsers = group.users.filter((u) => u !== userId);
				if (newUsers.length === 0) {
					reactions.splice(groupIdx, 1);
				} else {
					reactions[groupIdx] = { ...group, count: newUsers.length, users: newUsers, me: me ? false : group.me };
				}
			}
		}

		const updated = [...msgs];
		updated[msgIdx] = { ...msg, reactions };
		messagesByThread = new Map(messagesByThread);
		messagesByThread.set(threadId, updated);
		return;
	}
}

function bumpThreadActivity(threadId: string, channelId: string) {
	threadsByChannel = new Map(threadsByChannel);
	const existing = threadsByChannel.get(channelId);
	if (!existing) return;
	const thread = existing.find((t) => t.id === threadId);
	if (!thread) return;
	const updated = {
		...thread,
		reply_count: (thread.reply_count ?? 0) + 1,
		last_activity_at: new Date().toISOString()
	};
	threadsByChannel.set(
		channelId,
		[updated, ...existing.filter((t) => t.id !== threadId)]
	);
	if (activeThread && activeThread.id === threadId) {
		activeThread = updated;
	}
}
