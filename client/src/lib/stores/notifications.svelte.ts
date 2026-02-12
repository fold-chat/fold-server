import type { Message } from '$lib/api/messages.js';
import { closeMembersPanel } from '$lib/stores/membersPanel.svelte.js';
import { getMembers } from '$lib/stores/members.svelte.js';

export interface NotificationItem {
	message: Message;
	channelName: string;
	authorName: string;
	timestamp: number;
}

const MAX_NOTIFICATIONS = 100;

let notifications = $state<NotificationItem[]>([]);
let panelOpen = $state(false);
let lastOpenedAt = $state(Date.now());

export function getNotifications(): NotificationItem[] {
	return notifications;
}

export function addNotification(msg: Message, channelName: string) {
	const authorName = resolveAuthorName(msg);
	const item: NotificationItem = { message: msg, channelName, authorName, timestamp: Date.now() };
	notifications = [item, ...notifications].slice(0, MAX_NOTIFICATIONS);
}

function resolveAuthorName(msg: Message): string {
	if (msg.author_display_name) return msg.author_display_name;
	if (msg.author_username) return msg.author_username;
	const member = getMembers().find((m) => m.id === msg.author_id);
	if (member) return member.display_name || member.username;
	return 'Unknown';
}

export function isNotificationPanelOpen(): boolean {
	return panelOpen;
}

export function toggleNotificationPanel() {
	if (panelOpen) {
		panelOpen = false;
	} else {
		panelOpen = true;
		lastOpenedAt = Date.now();
		closeMembersPanel();
	}
}

export function closeNotificationPanel() {
	panelOpen = false;
}

export function getUnreadNotificationCount(): number {
	let count = 0;
	for (const n of notifications) {
		if (n.timestamp > lastOpenedAt) count++;
		else break; // sorted newest first
	}
	return count;
}
