export type BuiltInThemeId =
	| 'dark'
	| 'light'
	| 'abyss'
	| 'sakura'
	| 'forest'
	| 'sunset'
	| 'ocean'
	| 'nord'
	| 'retro'
	| 'candy';

export type ThemePreference = BuiltInThemeId | 'system';

export interface ThemeMeta {
	id: BuiltInThemeId;
	label: string;
	/** Preview swatch colors for the theme picker UI */
	colors: {
		bg: string;
		surface: string;
		accent: string;
		text: string;
		border: string;
	};
}

export const BUILT_IN_THEMES: ThemeMeta[] = [
	{ id: 'dark',   label: 'Dark',   colors: { bg: '#1a1a2e', surface: '#16213e', accent: '#e94560', text: '#e0e0e0', border: '#2a2a4a' } },
	{ id: 'light',  label: 'Light',  colors: { bg: '#f5f5f5', surface: '#ffffff', accent: '#e94560', text: '#1a1a2e', border: '#d0d0d0' } },
	{ id: 'abyss',  label: 'Abyss',  colors: { bg: '#050508', surface: '#0d0d14', accent: '#00c8ff', text: '#c8d4e8', border: '#1c1c2e' } },
	{ id: 'sakura', label: 'Sakura', colors: { bg: '#fdf0f5', surface: '#fff5f8', accent: '#d63384', text: '#2d1a24', border: '#f0c4d8' } },
	{ id: 'forest', label: 'Forest', colors: { bg: '#0b1a0d', surface: '#0f2212', accent: '#4ec94e', text: '#c8dcc0', border: '#1e3820' } },
	{ id: 'sunset', label: 'Sunset', colors: { bg: '#18091e', surface: '#240d2e', accent: '#ff6b35', text: '#f0d8e8', border: '#3a1548' } },
	{ id: 'ocean',  label: 'Ocean',  colors: { bg: '#08131f', surface: '#0d1b2a', accent: '#00c8b8', text: '#c0d8f0', border: '#142030' } },
	{ id: 'nord',   label: 'Nord',   colors: { bg: '#2e3440', surface: '#3b4252', accent: '#88c0d0', text: '#eceff4', border: '#3e4758' } },
	{ id: 'retro',  label: 'Retro',  colors: { bg: '#0a0800', surface: '#120f00', accent: '#ffb000', text: '#ffb000', border: '#2a2200' } },
	{ id: 'candy',  label: 'Candy',  colors: { bg: '#120818', surface: '#1c0e24', accent: '#e040fb', text: '#f0d0ff', border: '#2e1440' } },
];

const ALL_BUILT_IN_IDS = new Set<string>(BUILT_IN_THEMES.map((t) => t.id));

const STORAGE_KEY = 'kith_theme';

let preference = $state<ThemePreference>('system');

function getSystemTheme(): BuiltInThemeId {
	if (typeof window === 'undefined') return 'dark';
	return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function resolveTheme(pref: ThemePreference): BuiltInThemeId {
	return pref === 'system' ? getSystemTheme() : pref;
}

function applyTheme(themeId: BuiltInThemeId) {
	if (typeof document === 'undefined') return;
	document.documentElement.setAttribute('data-theme', themeId);
}

export function getThemePreference(): ThemePreference {
	return preference;
}

export function getResolvedTheme(): BuiltInThemeId {
	return resolveTheme(preference);
}

export function setThemePreference(pref: ThemePreference) {
	preference = pref;
	localStorage.setItem(STORAGE_KEY, pref);
	applyTheme(resolveTheme(pref));
}

export function initTheme() {
	try {
		const stored = localStorage.getItem(STORAGE_KEY);
		if (stored && (stored === 'system' || ALL_BUILT_IN_IDS.has(stored))) {
			preference = stored as ThemePreference;
		}
	} catch { /* ignore */ }

	applyTheme(resolveTheme(preference));

	// Listen for system theme changes when preference is 'system'
	window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => {
		if (preference === 'system') {
			applyTheme(getSystemTheme());
		}
	});
}
