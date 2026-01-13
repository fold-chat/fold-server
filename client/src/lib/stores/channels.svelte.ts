import type { Channel, Category } from '$lib/api/channels.js';

let channels = $state<Channel[]>([]);
let categories = $state<Category[]>([]);
let readStates = $state<Map<string, string | null>>(new Map());
let unreadCounts = $state<Map<string, number>>(new Map());

export function getChannels(): Channel[] {
	return channels;
}

export function getCategories(): Category[] {
	return categories;
}

export function getReadStates(): Map<string, string | null> {
	return readStates;
}

export function getUnreadCount(channelId: string): number {
	return unreadCounts.get(channelId) ?? 0;
}

export function setChannels(chs: Channel[]) {
	channels = chs.sort((a, b) => a.position - b.position);
}

export function setCategories(cats: Category[]) {
	categories = cats.sort((a, b) => a.position - b.position);
}

export function setReadStates(states: Array<{ channel_id: string; last_read_message_id: string | null }>) {
	readStates = new Map(states.map((s) => [s.channel_id, s.last_read_message_id]));
}

export function markChannelRead(channelId: string, messageId: string) {
	readStates = new Map(readStates);
	readStates.set(channelId, messageId);
	unreadCounts = new Map(unreadCounts);
	unreadCounts.set(channelId, 0);
}

export function incrementUnread(channelId: string) {
	unreadCounts = new Map(unreadCounts);
	unreadCounts.set(channelId, (unreadCounts.get(channelId) ?? 0) + 1);
}

export function addChannel(ch: Channel) {
	channels = [...channels, ch].sort((a, b) => a.position - b.position);
}

export function updateChannel(ch: Channel) {
	channels = channels.map((c) => (c.id === ch.id ? ch : c)).sort((a, b) => a.position - b.position);
}

export function removeChannel(id: string) {
	channels = channels.filter((c) => c.id !== id);
}

export function addCategory(cat: Category) {
	categories = [...categories, cat].sort((a, b) => a.position - b.position);
}

export function updateCategory(cat: Category) {
	categories = categories.map((c) => (c.id === cat.id ? cat : c)).sort((a, b) => a.position - b.position);
}

export function removeCategory(id: string) {
	categories = categories.filter((c) => c.id !== id);
}

/** Get channels grouped by category (null category = uncategorized) */
export function getChannelsByCategory(): { category: Category | null; channels: Channel[] }[] {
	const uncategorized = channels.filter((c) => !c.category_id);
	const grouped: { category: Category | null; channels: Channel[] }[] = [];

	if (uncategorized.length > 0) {
		grouped.push({ category: null, channels: uncategorized });
	}

	for (const cat of categories) {
		const catChannels = channels.filter((c) => c.category_id === cat.id);
		if (catChannels.length > 0 || true) {
			// Always show category even if empty
			grouped.push({ category: cat, channels: catChannels });
		}
	}

	return grouped;
}
