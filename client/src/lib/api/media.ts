import { api } from './client.js';

export interface MediaItem {
	id: string;
	title: string;
	url: string;
	preview_url: string;
	width: number;
	height: number;
}

export interface MediaSearchResponse {
	results: MediaItem[];
	next: string | null;
}

export function searchMedia(query: string, type: string = 'gif', limit: number = 20, pos?: string) {
	let path = `/media/search?q=${encodeURIComponent(query)}&type=${type}&limit=${limit}`;
	if (pos) path += `&pos=${encodeURIComponent(pos)}`;
	return api<MediaSearchResponse>(path);
}

export function trendingMedia(type: string = 'gif', limit: number = 20, pos?: string) {
	let path = `/media/trending?type=${type}&limit=${limit}`;
	if (pos) path += `&pos=${encodeURIComponent(pos)}`;
	return api<MediaSearchResponse>(path);
}
