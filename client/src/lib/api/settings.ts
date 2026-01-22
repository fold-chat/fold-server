import { api } from './client.js';

export interface ServerSettings {
	server_name: string | null;
	server_icon: string | null;
	server_description: string | null;
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
