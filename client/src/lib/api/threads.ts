import { api } from './client.js';
import type { Message } from './messages.js';

export interface Thread {
	id: string;
	channel_id: string;
	parent_message_id: string | null;
	title: string | null;
	author_id: string;
	created_at: string;
	last_activity_at: string;
	locked: number;
	pinned: number;
	author_username?: string;
	author_display_name?: string;
	author_avatar_url?: string;
	reply_count?: number;
	first_message?: Message;
	first_message_content?: string;
	last_reply_username?: string;
	last_reply_avatar_url?: string;
	last_reply_at?: string;
}

export interface ThreadReadState {
	thread_id: string;
	channel_id: string;
	last_read_message_id: string | null;
	unread_count: number;
}

export function createThread(channelId: string, data: { parent_message_id?: string; title?: string; content: string; attachment_ids?: string[] }) {
	return api<Thread>(`/channels/${channelId}/threads`, { method: 'POST', body: JSON.stringify(data) });
}

export function getThreads(channelId: string, opts?: { before?: string; limit?: number }) {
	const params = new URLSearchParams();
	if (opts?.before) params.set('before', opts.before);
	if (opts?.limit) params.set('limit', String(opts.limit));
	const qs = params.toString();
	return api<Thread[]>(`/channels/${channelId}/threads${qs ? '?' + qs : ''}`);
}

export function getThread(threadId: string) {
	return api<Thread>(`/threads/${threadId}`);
}

export function getThreadMessages(threadId: string, opts?: { before?: string; after?: string; limit?: number }) {
	const params = new URLSearchParams();
	if (opts?.before) params.set('before', opts.before);
	if (opts?.after) params.set('after', opts.after);
	if (opts?.limit) params.set('limit', String(opts.limit));
	const qs = params.toString();
	return api<Message[]>(`/threads/${threadId}/messages${qs ? '?' + qs : ''}`);
}

export function replyToThread(threadId: string, content: string, attachmentIds?: string[]) {
	return api<Message>(`/threads/${threadId}/messages`, {
		method: 'POST',
		body: JSON.stringify({ content, attachment_ids: attachmentIds })
	});
}

export function updateThread(threadId: string, data: { title?: string; locked?: number; pinned?: number }) {
	return api<Thread>(`/threads/${threadId}`, { method: 'PATCH', body: JSON.stringify(data) });
}

export function deleteThread(threadId: string) {
	return api<void>(`/threads/${threadId}`, { method: 'DELETE' });
}

export function updateThreadReadState(threadId: string, lastReadMessageId: string) {
	return api<void>(`/threads/${threadId}/read-state`, {
		method: 'PUT',
		body: JSON.stringify({ last_read_message_id: lastReadMessageId })
	});
}
