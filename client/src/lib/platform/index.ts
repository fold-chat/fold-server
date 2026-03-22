/** True when running inside Tauri desktop shell. */
export function isDesktop(): boolean {
	return typeof window !== 'undefined' && '__TAURI__' in window;
}

export { showNotification, requestNotificationPermission } from './notifications.js';
export { updateTrayBadge } from './tray.js';
export { setGlobalTheme } from './theme.js';
export {
	registerGlobalShortcut,
	unregisterGlobalShortcut,
	listenGlobalShortcuts,
	loadKeybinds,
	saveKeybinds,
	type Keybinds,
	DEFAULT_KEYBINDS
} from './shortcuts.js';
