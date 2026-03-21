/**
 * Desktop global shortcuts for voice: push-to-talk, mute toggle, deafen toggle.
 * Loads keybinds from store, registers them as OS-level global shortcuts,
 * and handles press/release events from the Rust backend.
 */
import { isDesktop } from './index.js';
import {
	loadKeybinds,
	saveKeybinds,
	registerGlobalShortcut,
	unregisterGlobalShortcut,
	listenGlobalShortcuts,
	type Keybinds,
	DEFAULT_KEYBINDS
} from './shortcuts.js';
import {
	pttKeyDown,
	pttKeyUp,
	toggleMute,
	toggleDeafen,
	setPttEnabled,
	setPttKey
} from '$lib/stores/voice.svelte.js';

let currentKeybinds: Keybinds = { ...DEFAULT_KEYBINDS };
let unlisten: (() => void) | null = null;

/** Initialize global voice shortcuts on desktop. Call once at app startup. */
export async function initVoiceShortcuts(): Promise<void> {
	if (!isDesktop()) return;

	currentKeybinds = await loadKeybinds();

	// Register any configured shortcuts
	await registerAll(currentKeybinds);

	// Sync PTT state with voice store
	if (currentKeybinds.pushToTalk) {
		setPttEnabled(true);
		setPttKey(currentKeybinds.pushToTalk);
	}

	// Listen for global shortcut events from Rust
	unlisten = await listenGlobalShortcuts((shortcut, state) => {
		if (currentKeybinds.pushToTalk && shortcut.includes(currentKeybinds.pushToTalk)) {
			if (state === 'pressed') pttKeyDown();
			else pttKeyUp();
		}
		if (state === 'pressed' && currentKeybinds.muteToggle && shortcut.includes(currentKeybinds.muteToggle)) {
			toggleMute();
		}
		if (state === 'pressed' && currentKeybinds.deafenToggle && shortcut.includes(currentKeybinds.deafenToggle)) {
			toggleDeafen();
		}
	});
}

/** Update keybinds. Unregisters old, registers new, persists. */
export async function updateVoiceKeybinds(newKeybinds: Keybinds): Promise<void> {
	// Unregister old
	await unregisterAll(currentKeybinds);

	currentKeybinds = { ...newKeybinds };

	// Register new
	await registerAll(currentKeybinds);

	// Sync PTT
	if (currentKeybinds.pushToTalk) {
		setPttEnabled(true);
		setPttKey(currentKeybinds.pushToTalk);
	} else {
		setPttEnabled(false);
		setPttKey(null);
	}

	await saveKeybinds(currentKeybinds);
}

export function getVoiceKeybinds(): Keybinds {
	return { ...currentKeybinds };
}

/** Cleanup on app teardown. */
export async function destroyVoiceShortcuts(): Promise<void> {
	if (unlisten) {
		unlisten();
		unlisten = null;
	}
	await unregisterAll(currentKeybinds);
}

async function registerAll(kb: Keybinds) {
	if (kb.pushToTalk) await registerGlobalShortcut(kb.pushToTalk);
	if (kb.muteToggle) await registerGlobalShortcut(kb.muteToggle);
	if (kb.deafenToggle) await registerGlobalShortcut(kb.deafenToggle);
}

async function unregisterAll(kb: Keybinds) {
	if (kb.pushToTalk) await unregisterGlobalShortcut(kb.pushToTalk);
	if (kb.muteToggle) await unregisterGlobalShortcut(kb.muteToggle);
	if (kb.deafenToggle) await unregisterGlobalShortcut(kb.deafenToggle);
}
