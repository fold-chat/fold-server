import { api, apiRaw, type ApiError } from './client.js';

export interface CustomEmoji {
	id: string;
	name: string;
	url: string;
	uploader_id: string;
}

export function getEmoji() {
	return api<CustomEmoji[]>('/emoji');
}

export async function uploadEmoji(file: File, name: string): Promise<CustomEmoji> {
	const form = new FormData();
	form.append('file', file);
	form.append('name', name);

	const res = await apiRaw('/emoji', {
		method: 'POST',
		body: form
	});

	if (!res.ok) {
		const err = (await res.json().catch(() => ({ error: 'upload_failed' }))) as ApiError;
		throw err;
	}

	return res.json();
}

export function deleteEmoji(id: string) {
	return api<void>(`/emoji/${id}`, { method: 'DELETE' });
}
