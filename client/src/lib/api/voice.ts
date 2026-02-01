import { api } from './client.js';

export interface VoiceTokenResponse {
	token: string;
	url: string;
	encryption_key?: string;
	key_index?: number;
}

export interface VoiceState {
	user_id: string;
	username: string;
	display_name: string | null;
	avatar_url: string | null;
	channel_id: string;
	self_mute: boolean;
	self_deaf: boolean;
	server_mute: boolean;
	server_deaf: boolean;
	joined_at: string;
}

export function getVoiceToken(channelId: string) {
	return api<VoiceTokenResponse>('/voice/token', {
		method: 'POST',
		body: JSON.stringify({ channel_id: channelId })
	});
}

export function leaveVoice() {
	return api<void>('/voice', { method: 'DELETE' });
}

export function updateVoiceState(data: { self_mute?: boolean; self_deaf?: boolean }) {
	return api<VoiceState>('/voice', { method: 'PATCH', body: JSON.stringify(data) });
}

export function serverMute(channelId: string, userId: string) {
	return api<void>(`/voice/${channelId}/mute/${userId}`, { method: 'POST' });
}

export function serverUnmute(channelId: string, userId: string) {
	return api<void>(`/voice/${channelId}/unmute/${userId}`, { method: 'POST' });
}

export function serverDeafen(channelId: string, userId: string) {
	return api<void>(`/voice/${channelId}/deafen/${userId}`, { method: 'POST' });
}

export function serverUndeafen(channelId: string, userId: string) {
	return api<void>(`/voice/${channelId}/undeafen/${userId}`, { method: 'POST' });
}

export function disconnectUser(channelId: string, userId: string) {
	return api<void>(`/voice/${channelId}/disconnect/${userId}`, { method: 'POST' });
}

export function moveUser(channelId: string, userId: string, targetChannelId: string) {
	return api<void>(`/voice/${channelId}/move/${userId}`, {
		method: 'POST',
		body: JSON.stringify({ target_channel_id: targetChannelId })
	});
}

export function rotateKey(channelId: string) {
	return api<void>(`/voice/${channelId}/rotate-key`, { method: 'POST' });
}

export interface VoiceRoomStats {
	channel_id: string;
	room_name: string;
	participants: number;
	num_publishers: number;
}

export interface VoiceStats {
	mode: string;
	status: string;
	active_connections: number;
	active_rooms: number;
	rooms: VoiceRoomStats[];
}

export function getVoiceStats() {
	return api<VoiceStats>('/voice/stats');
}
