import { isDesktop } from './index.js';

export interface Keybinds {
	pushToTalk: string | null;
	muteToggle: string | null;
	deafenToggle: string | null;
}

export const DEFAULT_KEYBINDS: Keybinds = {
	pushToTalk: null,
	muteToggle: null,
	deafenToggle: null
};

const KEYBIND_STORE_KEY = 'keybinds';

/** Register a global shortcut. No-op on web. */
export async function registerGlobalShortcut(shortcut: string): Promise<void> {
	if (!isDesktop() || !shortcut) return;
	const { register } = await import('@tauri-apps/plugin-global-shortcut');
	// Handler is on Rust side — it emits 'global-shortcut' event.
	// We pass a no-op handler since the Rust-level handler already emits events.
	await register(shortcut, () => {}).catch((e) => {
		console.warn(`[Platform] Failed to register shortcut ${shortcut}:`, e);
	});
}

/** Unregister a global shortcut. No-op on web. */
export async function unregisterGlobalShortcut(shortcut: string): Promise<void> {
	if (!isDesktop() || !shortcut) return;
	const mod = await import('@tauri-apps/plugin-global-shortcut');
	await mod.unregister(shortcut).catch(() => {});
}

type ShortcutCallback = (shortcut: string, state: 'pressed' | 'released') => void;

/**
 * Listen for global shortcut events from Rust.
 * Returns an unlisten function.
 */
export async function listenGlobalShortcuts(cb: ShortcutCallback): Promise<() => void> {
	if (!isDesktop()) return () => {};
	const { listen } = await import('@tauri-apps/api/event');
	const unlisten = await listen<{ shortcut: string; state: 'pressed' | 'released' }>(
		'global-shortcut',
		(event) => {
			cb(event.payload.shortcut, event.payload.state);
		}
	);
	return unlisten;
}

/** Load saved keybinds from tauri-plugin-store. Returns defaults on web or if not saved. */
export async function loadKeybinds(): Promise<Keybinds> {
	if (!isDesktop()) return { ...DEFAULT_KEYBINDS };
	try {
		const { load } = await import('@tauri-apps/plugin-store');
		const store = await load('settings.json');
		const saved = await store.get<Keybinds>(KEYBIND_STORE_KEY);
		return saved ? { ...DEFAULT_KEYBINDS, ...saved } : { ...DEFAULT_KEYBINDS };
	} catch {
		return { ...DEFAULT_KEYBINDS };
	}
}

/** Persist keybinds to tauri-plugin-store. No-op on web. */
export async function saveKeybinds(keybinds: Keybinds): Promise<void> {
	if (!isDesktop()) return;
	try {
		const { load } = await import('@tauri-apps/plugin-store');
		const store = await load('settings.json');
		await store.set(KEYBIND_STORE_KEY, keybinds);
		await store.save();
	} catch {
		// ignore
	}
}
