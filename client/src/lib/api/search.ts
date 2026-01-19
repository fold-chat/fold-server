import { api } from './client.js';
import type { Message } from './messages.js';

export interface SearchResult extends Message {
	snippet: string;
}

export interface SearchFilters {
	channel_id?: string;
	author_id?: string;
	before?: string;
	after?: string;
	has?: 'file' | 'image' | 'link';
	limit?: number;
}

export function searchMessages(query: string, filters?: SearchFilters) {
	const params = new URLSearchParams();
	params.set('q', query);
	if (filters?.channel_id) params.set('channel_id', filters.channel_id);
	if (filters?.author_id) params.set('author_id', filters.author_id);
	if (filters?.before) params.set('before', filters.before);
	if (filters?.after) params.set('after', filters.after);
	if (filters?.has) params.set('has', filters.has);
	if (filters?.limit) params.set('limit', String(filters.limit));
	return api<SearchResult[]>(`/search?${params.toString()}`);
}
