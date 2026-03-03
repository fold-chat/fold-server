import { api } from './client.js';

export interface ServerSettings {
	server_name: string | null;
	server_icon: string | null;
	server_description: string | null;
	maintenance_enabled?: boolean;
	maintenance_message?: string | null;
}

export interface MaintenanceStatus {
	maintenance_enabled: boolean;
	maintenance_message: string | null;
}

export function getServerSettings() {
	return api<ServerSettings>('/settings');
}

export function updateServerSettings(data: Partial<ServerSettings>) {
	return api<ServerSettings>('/settings', {
		method: 'PATCH',
		body: JSON.stringify(data)
	});
}

export function getMaintenanceStatus() {
	return api<MaintenanceStatus>('/admin/status');
}

export function disableServer(message?: string) {
	return api<MaintenanceStatus>('/admin/status/disable', {
		method: 'POST',
		body: JSON.stringify({ message: message || null })
	});
}

export function enableServer() {
	return api<MaintenanceStatus>('/admin/status/enable', {
		method: 'POST'
	});
}
