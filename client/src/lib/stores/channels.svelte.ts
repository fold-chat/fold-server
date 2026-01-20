import type { Channel, Category } from '$lib/api/channels.js';
import { hasServerPermission } from '$lib/stores/auth.svelte.js';
import { PermissionName } from '$lib/permissions.js';

let channels = $state<Channel[]>([]);
let categories = $state<Category[]>([]);
let readStates = $state<Map<string, string | null>>(new Map());
let unreadCounts = $state<Map<string, number>>(new Map());

export function getChannels(): Channel[] {
	return channels;
}

export function getChannelById(id: string): Channel | undefined {
	return channels.find((c) => c.id === id);
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

export function setUnreadCounts(counts: Array<{ channel_id: string; unread_count: number }>) {
	unreadCounts = new Map(counts.map((c) => [c.channel_id, c.unread_count]));
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

export function addChannel(ch: Channel & { category?: Category }) {
	if (ch.category) {
		ensureCategory(ch.category);
	}
	channels = [...channels, ch].sort((a, b) => a.position - b.position);
}

export function updateChannel(ch: Channel & { category?: Category }) {
	if (ch.category) {
		ensureCategory(ch.category);
	}
	channels = channels.map((c) => (c.id === ch.id ? ch : c)).sort((a, b) => a.position - b.position);
}

function ensureCategory(cat: Category) {
	if (!categories.some((c) => c.id === cat.id)) {
		categories = [...categories, cat].sort((a, b) => a.position - b.position);
	}
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

export function reorderCategoriesLocal(items: { id: string; position: number }[]) {
	const posMap = new Map(items.map((i) => [i.id, i.position]));
	categories = categories
		.map((c) => (posMap.has(c.id) ? { ...c, position: posMap.get(c.id)! } : c))
		.sort((a, b) => a.position - b.position);
}

export function reorderChannelsLocal(items: { id: string; position: number; category_id: string | null }[]) {
	const updates = new Map(items.map((i) => [i.id, i]));
	channels = channels
		.map((c) => {
			const u = updates.get(c.id);
			return u ? { ...c, position: u.position, category_id: u.category_id } : c;
		})
		.sort((a, b) => a.position - b.position);
}

/** Get channels grouped by category (null category = uncategorized) */
export function getChannelsByCategory(): { category: Category | null; channels: Channel[] }[] {
	const uncategorized = channels.filter((c) => !c.category_id);
	const grouped: { category: Category | null; channels: Channel[] }[] = [];

	if (uncategorized.length > 0) {
		grouped.push({ category: null, channels: uncategorized });
	}

	const showAll = hasServerPermission(PermissionName.MANAGE_CHANNELS);
	for (const cat of categories) {
		const catChannels = channels.filter((c) => c.category_id === cat.id);
		if (catChannels.length > 0 || showAll) {
			grouped.push({ category: cat, channels: catChannels });
		}
	}

	return grouped;
}
