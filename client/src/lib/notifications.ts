import type { Message } from '$lib/api/messages.js';

let permissionRequested = false;

/** Request Notification permission if not already granted. */
function ensurePermission() {
	if (permissionRequested || typeof Notification === 'undefined') return;
	if (Notification.permission === 'default') {
		permissionRequested = true;
		Notification.requestPermission();
	}
}

/** Show a desktop notification for a mention, only when tab is not focused. */
export function showMentionNotification(msg: Message, channelName?: string) {
	if (typeof Notification === 'undefined') return;
	ensurePermission();

	if (document.hasFocus()) return;
	if (Notification.permission !== 'granted') return;

	const author = msg.author_display_name || msg.author_username || 'Someone';
	const title = channelName ? `#${channelName}` : 'New mention';
	const body = `${author}: ${msg.content?.slice(0, 200) || '(attachment)'}`;

	const n = new Notification(title, { body, tag: `mention-${msg.id}` });
	n.onclick = () => {
		window.focus();
		window.location.href = `/channels/${msg.channel_id}`;
		n.close();
	};
}
