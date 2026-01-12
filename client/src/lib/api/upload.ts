import { apiRaw, type ApiError } from './client.js';

export interface UploadResult {
	id: string;
	url: string;
}

export async function uploadFile(file: File): Promise<UploadResult> {
	const formData = new FormData();
	formData.append('file', file);

	const res = await apiRaw('/upload', {
		method: 'POST',
		body: formData
	});

	if (!res.ok) {
		const err = (await res.json().catch(() => ({ error: 'upload_failed' }))) as ApiError;
		throw err;
	}

	return res.json();
}
