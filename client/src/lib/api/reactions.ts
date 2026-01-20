import { api } from './client.js';

export function addReaction(messageId: string, emoji: string) {
	return api<void>(`/messages/${messageId}/reactions/${encodeURIComponent(emoji)}`, {
		method: 'PUT'
	});
}

export function removeReaction(messageId: string, emoji: string) {
	return api<void>(`/messages/${messageId}/reactions/${encodeURIComponent(emoji)}`, {
		method: 'DELETE'
	});
}
