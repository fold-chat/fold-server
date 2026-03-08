import { api } from './client.js';

export interface Role {
	id: string;
	name: string;
	color: string | null;
	position: number;
	permissions: string[];
	is_default: number;
	created_at: string;
}

export interface ChannelPermissionOverride {
	id: string;
	channel_id: string;
	role_id: string;
	allow: string[];
	deny: string[];
	created_at: string;
}

export function getRoles() {
	return api<Role[]>('/roles');
}

export function createRole(data: {
	name: string;
	permissions: string[];
	color?: string | null;
}) {
	return api<Role>('/roles', { method: 'POST', body: JSON.stringify(data) });
}

export function updateRole(
	id: string,
	data: { name?: string; permissions?: string[]; color?: string | null }
) {
	return api<Role>(`/roles/${id}`, { method: 'PATCH', body: JSON.stringify(data) });
}

export function reorderRoles(items: { id: string; position: number }[]) {
	return api<Role[]>('/roles/reorder', { method: 'PATCH', body: JSON.stringify(items) });
}

export function deleteRole(id: string) {
	return api<{ deleted: boolean; affected_users: number }>(`/roles/${id}`, { method: 'DELETE' });
}

export function setDefaultRole(id: string) {
	return api<Role>(`/roles/${id}/default`, { method: 'PUT' });
}

export function assignRole(userId: string, roleId: string) {
	return api<void>(`/members/${userId}/roles/${roleId}`, { method: 'PUT' });
}

export function removeRole(userId: string, roleId: string) {
	return api<void>(`/members/${userId}/roles/${roleId}`, { method: 'DELETE' });
}

export function getChannelPermissions(channelId: string) {
	return api<ChannelPermissionOverride[]>(`/channels/${channelId}/permissions`);
}

export function updateChannelPermission(
	channelId: string,
	roleId: string,
	data: { allow: string[]; deny: string[] }
) {
	return api<ChannelPermissionOverride>(`/channels/${channelId}/permissions/${roleId}`, {
		method: 'PUT',
		body: JSON.stringify(data)
	});
}

export function deleteChannelPermission(channelId: string, roleId: string) {
	return api<void>(`/channels/${channelId}/permissions/${roleId}`, { method: 'DELETE' });
}
