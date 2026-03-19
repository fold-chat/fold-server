import { api } from './client.js';

export interface DmParticipant {
	id: string;
	username: string;
	display_name: string | null;
	avatar_url: string | null;
}

export interface DmConversation {
	channel_id: string;
	participants: DmParticipant[];
	last_activity_at: string;
	is_blocked: boolean;
}

export function openDm(userId: string) {
	return api<DmConversation>('/dm', {
		method: 'POST',
		body: JSON.stringify({ user_id: userId })
	});
}

export function getDmConversations() {
	return api<DmConversation[]>('/dm');
}

export function blockUser(userId: string) {
	return api<void>('/dm/block', {
		method: 'POST',
		body: JSON.stringify({ user_id: userId })
	});
}

export function unblockUser(userId: string) {
	return api<void>(`/dm/block/${userId}`, { method: 'DELETE' });
}

export function getBlocks() {
	return api<string[]>('/dm/blocks');
}
