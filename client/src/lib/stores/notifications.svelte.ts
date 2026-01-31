import type { Message } from '$lib/api/messages.js';
import { closeMembersPanel } from '$lib/stores/membersPanel.svelte.js';

export interface NotificationItem {
	message: Message;
	channelName: string;
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
	const item: NotificationItem = { message: msg, channelName, timestamp: Date.now() };
	notifications = [item, ...notifications].slice(0, MAX_NOTIFICATIONS);
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
