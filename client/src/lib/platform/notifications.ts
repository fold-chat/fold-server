import { isDesktop } from './index.js';

let webPermissionRequested = false;

/** Request notification permission (Tauri or web). */
export async function requestNotificationPermission(): Promise<boolean> {
	if (isDesktop()) {
		const { isPermissionGranted, requestPermission } = await import(
			'@tauri-apps/plugin-notification'
		);
		if (await isPermissionGranted()) return true;
		const result = await requestPermission();
		return result === 'granted';
	}
	// Web fallback
	if (typeof Notification === 'undefined') return false;
	if (Notification.permission === 'granted') return true;
	if (!webPermissionRequested) {
		webPermissionRequested = true;
		const result = await Notification.requestPermission();
		return result === 'granted';
	}
	return (Notification.permission as string) === 'granted';
}

/** Show a notification. On desktop uses native notifications; on web uses Notification API. */
export async function showNotification(opts: {
	title: string;
	body: string;
	tag?: string;
	channelId?: string;
}): Promise<void> {
	if (isDesktop()) {
		const { sendNotification, isPermissionGranted } = await import(
			'@tauri-apps/plugin-notification'
		);
		if (!(await isPermissionGranted())) return;
		sendNotification({ title: opts.title, body: opts.body });
		return;
	}

	// Web fallback
	if (typeof Notification === 'undefined') return;
	if (document.hasFocus()) return;
	if ((Notification.permission as string) !== 'granted') {
		await requestNotificationPermission();
		if ((Notification.permission as string) !== 'granted') return;
	}

	const n = new Notification(opts.title, { body: opts.body, tag: opts.tag });
	n.onclick = () => {
		window.focus();
		if (opts.channelId) {
			window.location.href = `/channels/${opts.channelId}`;
		}
		n.close();
	};
}
