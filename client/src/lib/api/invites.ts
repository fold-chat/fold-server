import { api } from './client.js';

export interface Invite {
	id: string;
	code: string;
	creator_id: string;
	description: string;
	max_uses: number | null;
	use_count: number;
	expires_at: string | null;
	revoked_at: string | null;
	created_at: string;
}

export interface InviteInfo {
	code: string;
	valid: boolean;
	expires_at: string;
}

export function createInvite(data: { description: string; max_uses?: number; expires_at?: string }) {
	return api<Invite>('/invites', {
		method: 'POST',
		body: JSON.stringify(data)
	});
}

export function getInvites() {
	return api<Invite[]>('/invites');
}

export function revokeInvite(code: string) {
	return api<Invite>(`/invites/${code}`, { method: 'DELETE' });
}

export function reinstateInvite(code: string) {
	return api<Invite>(`/invites/${code}/reinstate`, { method: 'POST' });
}

export function getInviteInfo(code: string) {
	return api<InviteInfo>(`/invites/${code}`);
}
