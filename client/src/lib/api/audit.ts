import { api } from './client.js';

export interface AuditLogEntry {
	id: string;
	actor_id: string | null;
	actor_username: string | null;
	action: string;
	target_type: string | null;
	target_id: string | null;
	details: string | null;
	created_at: string;
}

export interface AuditLogResponse {
	entries: AuditLogEntry[];
}

export function getAuditLog(params?: { limit?: number; before?: string; action?: string }) {
	const query = new URLSearchParams();
	if (params?.limit) query.set('limit', params.limit.toString());
	if (params?.before) query.set('before', params.before);
	if (params?.action) query.set('action', params.action);
	const path = `/audit-log${query.toString() ? '?' + query.toString() : ''}`;
	return api<AuditLogResponse>(path);
}
