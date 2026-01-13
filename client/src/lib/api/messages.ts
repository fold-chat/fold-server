import { api, apiRaw } from './client.js';

export interface Message {
	id: string;
	channel_id: string;
	author_id: string;
	thread_id: string | null;
	content: string;
	edited_at: string | null;
	pinned: number;
	created_at: string;
	author_username?: string;
	author_display_name?: string;
	author_avatar_url?: string;
	attachments?: FileAttachment[];
}

export interface FileAttachment {
	id: string;
	original_name: string;
	stored_name: string;
	mime_type: string;
	size_bytes: number;
	url: string;
}

export function getMessages(channelId: string, opts?: { before?: string; after?: string; limit?: number }) {
	const params = new URLSearchParams();
	if (opts?.before) params.set('before', opts.before);
	if (opts?.after) params.set('after', opts.after);
	if (opts?.limit) params.set('limit', String(opts.limit));
	const qs = params.toString();
	return api<Message[]>(`/channels/${channelId}/messages${qs ? '?' + qs : ''}`);
}

export function sendMessage(channelId: string, content: string, attachmentIds?: string[]) {
	return api<Message>(`/channels/${channelId}/messages`, {
		method: 'POST',
		body: JSON.stringify({ content, attachment_ids: attachmentIds })
	});
}

export async function uploadFile(file: File): Promise<{ id: string; url: string }> {
	const form = new FormData();
	form.append('file', file);
	const res = await apiRaw('/upload', {
		method: 'POST',
		body: form
	});
	if (!res.ok) {
		const err = await res.json().catch(() => ({ error: 'upload_failed' }));
		throw err;
	}
	return res.json();
}

export function editMessage(id: string, content: string) {
	return api<Message>(`/messages/${id}`, {
		method: 'PATCH',
		body: JSON.stringify({ content })
	});
}

export function deleteMessage(id: string) {
	return api<void>(`/messages/${id}`, { method: 'DELETE' });
}

export function updateReadState(channelId: string, lastReadMessageId: string) {
	return api<void>(`/channels/${channelId}/read-state`, {
		method: 'PUT',
		body: JSON.stringify({ last_read_message_id: lastReadMessageId })
	});
}
