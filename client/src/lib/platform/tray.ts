import { isDesktop } from './index.js';

/** Update tray icon badge / dock badge with unread count. No-op on web. */
export async function updateTrayBadge(count: number): Promise<void> {
	if (!isDesktop()) return;
	const { invoke } = await import('@tauri-apps/api/core');
	await invoke('update_tray_badge', { count }).catch(() => {});
}
