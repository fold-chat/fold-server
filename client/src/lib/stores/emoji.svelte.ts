import type { CustomEmoji } from '$lib/api/emoji.js';

let customEmoji = $state<CustomEmoji[]>([]);

export function getCustomEmoji(): CustomEmoji[] {
	return customEmoji;
}

export function setCustomEmoji(emoji: CustomEmoji[]) {
	customEmoji = [...emoji];
}

export function addCustomEmoji(emoji: CustomEmoji) {
	if (customEmoji.some((e) => e.id === emoji.id)) return;
	customEmoji = [...customEmoji, emoji];
}

export function removeCustomEmoji(id: string) {
	customEmoji = customEmoji.filter((e) => e.id !== id);
}

/** Find a custom emoji by shortcode name */
export function findCustomEmojiByName(name: string): CustomEmoji | undefined {
	return customEmoji.find((e) => e.name === name);
}

/** Search custom emoji by name */
export function searchCustomEmoji(query: string): CustomEmoji[] {
	const q = query.toLowerCase().trim();
	if (!q) return [];
	return customEmoji.filter((e) => e.name.toLowerCase().includes(q));
}
