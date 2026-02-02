import { api } from './client.js';

export interface Channel {
	id: string;
	name: string;
	type: string;
	category_id: string | null;
	topic: string | null;
	description: string | null;
	position: number;
	created_at: string;
	settings: string | null;
	icon: string | null;
	icon_url: string | null;
}

export interface Category {
	id: string;
	name: string;
	position: number;
	created_at: string;
}

export function getChannels() {
	return api<Channel[]>('/channels');
}

export function createChannel(data: { name: string; type?: string; category_id?: string | null; topic?: string; description?: string; icon?: string | null; icon_url?: string | null }) {
	return api<Channel>('/channels', { method: 'POST', body: JSON.stringify(data) });
}

export function updateChannel(id: string, data: Partial<Pick<Channel, 'name' | 'topic' | 'description' | 'category_id' | 'position' | 'icon' | 'icon_url'>>) {
	return api<Channel>(`/channels/${id}`, { method: 'PATCH', body: JSON.stringify(data) });
}

export function deleteChannel(id: string) {
	return api<void>(`/channels/${id}`, { method: 'DELETE' });
}

export function getCategories() {
	return api<Category[]>('/categories');
}

export function createCategory(data: { name: string; position?: number }) {
	return api<Category>('/categories', { method: 'POST', body: JSON.stringify(data) });
}

export function updateCategory(id: string, data: { name?: string; position?: number }) {
	return api<Category>(`/categories/${id}`, { method: 'PATCH', body: JSON.stringify(data) });
}

export function deleteCategory(id: string) {
	return api<void>(`/categories/${id}`, { method: 'DELETE' });
}

export function reorderCategories(items: { id: string; position: number }[]) {
	return api<Category[]>('/categories/reorder', { method: 'PATCH', body: JSON.stringify(items) });
}

export function reorderChannels(items: { id: string; position: number; category_id: string | null }[]) {
	return api<Channel[]>('/channels/reorder', { method: 'PATCH', body: JSON.stringify(items) });
}
