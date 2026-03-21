import type { Message } from '$lib/api/messages.js';
import { contentPreview } from '$lib/utils/markdown.js';
import { showNotification, requestNotificationPermission } from '$lib/platform/index.js';

/** Request notification permission on first load. */
export function ensurePermission() {
	requestNotificationPermission();
}

/** Show a desktop notification for a mention (native on desktop, web Notification API on browser). */
export function showMentionNotification(msg: Message, channelName?: string) {
	const author = msg.author_display_name || msg.author_username || 'Someone';
	const title = channelName ? `#${channelName}` : 'New mention';
	const body = `${author}: ${contentPreview(msg.content, 200, msg.mentions)}`;

	showNotification({
		title,
		body,
		tag: `mention-${msg.id}`,
		channelId: msg.channel_id
	});
}
