export interface Shortcut {
	keys: string;
	description: string;
}

export const shortcuts: Shortcut[] = [
	{ keys: '⌘K', description: 'Search messages' },
	{ keys: '⌘/', description: 'Show keyboard shortcuts' },
	{ keys: 'Escape', description: 'Close modal / panel' },
	{ keys: 'Alt+↑', description: 'Previous channel' },
	{ keys: 'Alt+↓', description: 'Next channel' },
	{ keys: '⌘E', description: 'Toggle emoji picker' },
];

let helpOpen = $state(false);

export function isShortcutHelpOpen(): boolean {
	return helpOpen;
}

export function openShortcutHelp() {
	helpOpen = true;
}

export function closeShortcutHelp() {
	helpOpen = false;
}

export function toggleShortcutHelp() {
	helpOpen = !helpOpen;
}
