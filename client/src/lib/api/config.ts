import { api } from './client.js';

export type RuntimeConfig = Record<string, string | null>;

export function getRuntimeConfig() {
	return api<RuntimeConfig>('/config');
}

export function updateRuntimeConfig(data: RuntimeConfig) {
	return api<RuntimeConfig>('/config', {
		method: 'PATCH',
		body: JSON.stringify(data)
	});
}
