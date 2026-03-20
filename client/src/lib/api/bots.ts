import { api, apiRaw } from './client.js';
import type { RoleBadge } from './users.js';

export interface Bot {
	id: string;
	username: string;
	display_name: string | null;
	avatar_url: string | null;
	is_bot: boolean;
	bot_enabled: number;
	created_at: string;
	token_id: string | null;
	token_created_at: string | null;
	token_last_used_at: string | null;
	roles?: string | RoleBadge[];
}

export interface CreateBotResult extends Bot {
	token: string;
}

export interface RegenerateTokenResult {
	bot_id: string;
	token: string;
	old_token_revoked: boolean;
	message: string;
}

export function listBots() {
	return api<Bot[]>('/bots');
}

export function createBot(data: { username: string; display_name?: string }) {
	return api<CreateBotResult>('/bots', {
		method: 'POST',
		body: JSON.stringify(data)
	});
}

export function updateBot(id: string, data: { display_name?: string }) {
	return api<Bot>(`/bots/${id}`, {
		method: 'PATCH',
		body: JSON.stringify(data)
	});
}

export async function uploadBotAvatar(id: string, file: File): Promise<Bot> {
	const formData = new FormData();
	formData.append('file', file);
	const res = await apiRaw(`/bots/${id}/avatar`, {
		method: 'POST',
		body: formData
	});
	if (!res.ok) {
		const err = await res.json().catch(() => ({ error: 'upload_failed' }));
		throw err;
	}
	return res.json();
}

export function regenerateToken(id: string) {
	return api<RegenerateTokenResult>(`/bots/${id}/regenerate-token`, {
		method: 'POST',
		body: JSON.stringify({})
	});
}

export function enableBot(id: string) {
	return api<Bot>(`/bots/${id}/enable`, { method: 'POST' });
}

export function disableBot(id: string) {
	return api<Bot>(`/bots/${id}/disable`, { method: 'POST' });
}

export function deleteBot(id: string) {
	return api<void>(`/bots/${id}`, { method: 'DELETE' });
}
