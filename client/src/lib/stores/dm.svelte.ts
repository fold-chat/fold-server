import type { DmConversation } from '$lib/api/dm.js';
import { getDmConversations as fetchDmConversationsApi, getBlocks as fetchBlocksApi } from '$lib/api/dm.js';

let conversations = $state<DmConversation[]>([]);
let blockedUserIds = $state<Set<string>>(new Set());
let dmUnreadCounts = $state<Map<string, number>>(new Map());
let dmMentionCounts = $state<Map<string, number>>(new Map());
let dmLoaded = $state(false);
let dmLoading = $state(false);

// --- Conversations ---

export function getDmConversations(): DmConversation[] {
	return conversations;
}

export function setDmConversations(convs: DmConversation[]) {
	conversations = convs.sort((a, b) => b.last_activity_at.localeCompare(a.last_activity_at));
}

export function addDmConversation(conv: DmConversation) {
	// Avoid duplicates
	if (conversations.some((c) => c.channel_id === conv.channel_id)) return;
	conversations = [conv, ...conversations];
}

export function updateDmLastActivity(channelId: string) {
	const idx = conversations.findIndex((c) => c.channel_id === channelId);
	if (idx === -1) return;
	const updated = { ...conversations[idx], last_activity_at: new Date().toISOString() };
	conversations = [updated, ...conversations.filter((c) => c.channel_id !== channelId)];
}

export function getDmConversation(channelId: string): DmConversation | undefined {
	return conversations.find((c) => c.channel_id === channelId);
}

export function updateDmConversation(channelId: string, updates: Partial<DmConversation>) {
	conversations = conversations.map((c) =>
		c.channel_id === channelId ? { ...c, ...updates } : c
	);
}

// --- Blocked users ---

export function getBlockedUserIds(): Set<string> {
	return blockedUserIds;
}

export function setBlockedUserIds(ids: string[]) {
	blockedUserIds = new Set(ids);
}

export function addBlock(userId: string) {
	blockedUserIds = new Set([...blockedUserIds, userId]);
}

export function removeBlock(userId: string) {
	const next = new Set(blockedUserIds);
	next.delete(userId);
	blockedUserIds = next;
}

export function isBlocked(userId: string): boolean {
	return blockedUserIds.has(userId);
}

// --- Unread counts ---

export function getDmUnreadCount(channelId: string): number {
	return dmUnreadCounts.get(channelId) ?? 0;
}

export function getTotalDmUnreadCount(): number {
	let total = 0;
	for (const c of dmUnreadCounts.values()) total += c;
	return total;
}

export function setDmUnreadCounts(
	counts: Array<{ channel_id: string; unread_count: number; mention_count?: number }>
) {
	dmUnreadCounts = new Map(counts.map((c) => [c.channel_id, c.unread_count]));
	dmMentionCounts = new Map(
		counts.filter((c) => c.mention_count).map((c) => [c.channel_id, c.mention_count!])
	);
}

export function markDmRead(channelId: string) {
	dmUnreadCounts = new Map(dmUnreadCounts);
	dmUnreadCounts.set(channelId, 0);
	dmMentionCounts = new Map(dmMentionCounts);
	dmMentionCounts.set(channelId, 0);
}

export function incrementDmUnread(channelId: string) {
	dmUnreadCounts = new Map(dmUnreadCounts);
	dmUnreadCounts.set(channelId, (dmUnreadCounts.get(channelId) ?? 0) + 1);
}

/** Check if a channel ID belongs to a DM conversation */
export function isDmChannel(channelId: string): boolean {
	return conversations.some((c) => c.channel_id === channelId);
}

/** Lazy-load DM data on first access */
export async function ensureDmLoaded(): Promise<void> {
	if (dmLoaded || dmLoading) return;
	dmLoading = true;
	try {
		const [convs, blocks] = await Promise.all([
			fetchDmConversationsApi(),
			fetchBlocksApi()
		]);
		if (!dmLoaded) {
			setDmConversations(convs);
			setBlockedUserIds(blocks);
			dmLoaded = true;
		}
	} catch {
		// ignore — will retry on next access
	} finally {
		dmLoading = false;
	}
}

export function isDmLoaded(): boolean {
	return dmLoaded;
}

export function resetDmLoaded() {
	dmLoaded = false;
}
