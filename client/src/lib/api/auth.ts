import { api } from './client.js';

export interface LoginResponse {
	expires_in: number;
	password_must_change?: boolean;
}

export interface RegisterResponse {
	user_id: string;
	username: string;
	expires_in: number;
}

export interface SetupStatusResponse {
	setup_required: boolean;
	insecure: boolean;
}

export function login(username: string, password: string) {
	return api<LoginResponse>('/auth/login', {
		method: 'POST',
		body: JSON.stringify({ username, password })
	});
}

export function register(data: {
	username: string;
	password: string;
	invite_code?: string;
	server_password?: string;
}) {
	return api<RegisterResponse>('/auth/register', {
		method: 'POST',
		body: JSON.stringify(data)
	});
}

export function logout() {
	return api<void>('/auth/session', { method: 'DELETE' });
}

export function changePassword(current_password: string, new_password: string) {
	return api<{ message: string }>('/auth/password', {
		method: 'PATCH',
		body: JSON.stringify({ current_password, new_password })
	});
}

export function forceChangePassword(new_password: string) {
	return api<{ message: string }>('/auth/force-change-password', {
		method: 'POST',
		body: JSON.stringify({ new_password })
	});
}

export function getSetupStatus() {
	return api<SetupStatusResponse>('/setup/status');
}

export function setupAccount(username: string, password: string) {
	return api<{ user_id: string; username: string }>('/setup', {
		method: 'POST',
		body: JSON.stringify({ username, password })
	});
}
