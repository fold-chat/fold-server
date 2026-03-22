import { isDesktop } from './index.js';
import type { CustomTheme } from '$lib/stores/theme.svelte.js';

/** Push current theme to all server webviews via Tauri IPC. No-op on web. */
export async function setGlobalTheme(pref: string, customThemes: CustomTheme[]): Promise<void> {
	if (!isDesktop()) return;
	const { invoke } = await import('@tauri-apps/api/core');
	await invoke('set_global_theme', {
		themePref: pref,
		customThemesJson: JSON.stringify(customThemes),
	}).catch(() => {});
}
