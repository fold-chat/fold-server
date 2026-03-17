import { api } from './client.js';

export interface User {
	id: string;
	username: string;
	display_name: string | null;
	avatar_url: string | null;
	status_preference: string;
	status_text: string | null;
	bio: string | null;
	created_at: string;
	last_seen_at: string | null;
	roles?: string[];
}

export interface RoleBadge {
	id: string;
	name: string;
	color: string | null;
}

export interface Member {
	id: string;
	username: string;
	display_name: string | null;
	avatar_url: string | null;
	status_preference: string;
	status_text: string | null;
	bio: string | null;
	created_at: string;
	last_seen_at: string | null;
	roles: RoleBadge[];
	join_method: string | null;
	invite_description: string | null;
	banned_at: string | null;
	banned_by: string | null;
	ban_reason: string | null;
	banned_by_username: string | null;
}

export function getMe() {
	return api<User>('/users/@me');
}

export function updateMe(data: {
	display_name?: string;
	bio?: string;
	status_preference?: string;
	status_text?: string;
	avatar_url?: string;
}) {
	return api<User>('/users/@me', {
		method: 'PATCH',
		body: JSON.stringify(data)
	});
}

export function getUser(id: string) {
	return api<User>(`/users/${id}`);
}

export function getMembers() {
	return api<Member[]>('/members');
}

export function deleteMe(password: string) {
	return api<void>('/users/@me', {
		method: 'DELETE',
		body: JSON.stringify({ password })
	});
}

export function banMember(id: string, reason?: string) {
	return api<void>(`/members/${id}/ban`, {
		method: 'POST',
		body: JSON.stringify({ reason: reason || null })
	});
}

export function unbanMember(id: string) {
	return api<void>(`/members/${id}/ban`, { method: 'DELETE' });
}

export function resetMemberPassword(id: string, new_password: string) {
	return api<void>(`/members/${id}/reset-password`, {
		method: 'POST',
		body: JSON.stringify({ new_password })
	});
}
