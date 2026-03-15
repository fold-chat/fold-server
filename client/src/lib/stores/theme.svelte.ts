import { darkenHex, isColorDark } from '$lib/utils/color.js';

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

/** Built-in ID, 'system', or a custom theme UUID */
export type ThemePreference = BuiltInThemeId | 'system' | (string & {});

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

export interface CustomThemeColors {
	bg: string;
	'bg-surface': string;
	'bg-input': string;
	text: string;
	'text-muted': string;
	accent: string;
	border: string;
	success: string;
	danger: string;
	radius: string;
}

export interface CustomTheme {
	id: string;
	name: string;
	colors: CustomThemeColors;
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

export const BUILT_IN_THEME_COLORS: Record<BuiltInThemeId, CustomThemeColors> = {
	dark:   { bg: '#1a1a2e', 'bg-surface': '#16213e', 'bg-input': '#0f3460', text: '#e0e0e0', 'text-muted': '#888888', accent: '#e94560', border: '#2a2a4a', success: '#4caf50', danger: '#e74c3c', radius: '8px' },
	light:  { bg: '#f5f5f5', 'bg-surface': '#ffffff', 'bg-input': '#e8e8e8', text: '#1a1a2e', 'text-muted': '#666666', accent: '#e94560', border: '#d0d0d0', success: '#2e7d32', danger: '#c0392b', radius: '8px' },
	abyss:  { bg: '#050508', 'bg-surface': '#0d0d14', 'bg-input': '#141420', text: '#c8d4e8', 'text-muted': '#607090', accent: '#00c8ff', border: '#1c1c2e', success: '#00e676', danger: '#ff3d3d', radius: '8px' },
	sakura: { bg: '#fdf0f5', 'bg-surface': '#fff5f8', 'bg-input': '#fce4ef', text: '#2d1a24', 'text-muted': '#8a6070', accent: '#d63384', border: '#f0c4d8', success: '#2e7d32', danger: '#c0392b', radius: '8px' },
	forest: { bg: '#0b1a0d', 'bg-surface': '#0f2212', 'bg-input': '#162e19', text: '#c8dcc0', 'text-muted': '#6a8c68', accent: '#4ec94e', border: '#1e3820', success: '#69f069', danger: '#e74c3c', radius: '8px' },
	sunset: { bg: '#18091e', 'bg-surface': '#240d2e', 'bg-input': '#30113c', text: '#f0d8e8', 'text-muted': '#9a7090', accent: '#ff6b35', border: '#3a1548', success: '#66bb6a', danger: '#e74c3c', radius: '8px' },
	ocean:  { bg: '#08131f', 'bg-surface': '#0d1b2a', 'bg-input': '#0f2337', text: '#c0d8f0', 'text-muted': '#5a8090', accent: '#00c8b8', border: '#142030', success: '#00e5b0', danger: '#e74c3c', radius: '8px' },
	nord:   { bg: '#2e3440', 'bg-surface': '#3b4252', 'bg-input': '#434c5e', text: '#eceff4', 'text-muted': '#9099a5', accent: '#88c0d0', border: '#3e4758', success: '#a3be8c', danger: '#bf616a', radius: '8px' },
	retro:  { bg: '#0a0800', 'bg-surface': '#120f00', 'bg-input': '#1c1800', text: '#ffb000', 'text-muted': '#8a6000', accent: '#ffb000', border: '#2a2200', success: '#70ff70', danger: '#ff4500', radius: '8px' },
	candy:  { bg: '#120818', 'bg-surface': '#1c0e24', 'bg-input': '#261430', text: '#f0d0ff', 'text-muted': '#9060a8', accent: '#e040fb', border: '#2e1440', success: '#69f0ae', danger: '#e74c3c', radius: '8px' },
};

const ALL_BUILT_IN_IDS = new Set<string>(BUILT_IN_THEMES.map((t) => t.id));

const STORAGE_KEY = 'fold_theme';
const CUSTOM_STORAGE_KEY = 'fold_custom_themes';

let preference = $state<string>('system');
let customThemes = $state<CustomTheme[]>([]);

// Eagerly load from localStorage at module init (browser only).
// This ensures $state values are correct before any component reads them.
if (typeof window !== 'undefined') {
	let parsedCustom: CustomTheme[] = [];
	try {
		const raw = localStorage.getItem(CUSTOM_STORAGE_KEY);
		if (raw) parsedCustom = JSON.parse(raw);
	} catch { /* ignore */ }
	customThemes = parsedCustom;

	try {
		const stored = localStorage.getItem(STORAGE_KEY);
		if (stored) {
			const valid =
				stored === 'system' ||
				ALL_BUILT_IN_IDS.has(stored) ||
				parsedCustom.some((t: CustomTheme) => t.id === stored);
			if (valid) preference = stored;
		}
	} catch { /* ignore */ }
}

function getSystemTheme(): BuiltInThemeId {
	if (typeof window === 'undefined') return 'dark';
	return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function resolveTheme(pref: string): string {
	return pref === 'system' ? getSystemTheme() : pref;
}

function applyCustomThemeVars(theme: CustomTheme): void {
	const { colors } = theme;
	const el = document.documentElement;
	const isDark = isColorDark(colors.bg);
	el.setAttribute('data-theme', isDark ? 'dark' : 'light');
	const s = el.style;
	s.setProperty('--bg', colors.bg);
	s.setProperty('--bg-surface', colors['bg-surface']);
	s.setProperty('--bg-input', colors['bg-input']);
	s.setProperty('--text', colors.text);
	s.setProperty('--text-muted', colors['text-muted']);
	s.setProperty('--accent', colors.accent);
	s.setProperty('--accent-hover', darkenHex(colors.accent, 0.12));
	s.setProperty('--primary', colors.accent);
	s.setProperty('--border', colors.border);
	s.setProperty('--success', colors.success);
	s.setProperty('--danger', colors.danger);
	s.setProperty('--error', colors.danger);
	s.setProperty('--radius', colors.radius);
	s.setProperty('--bg-hover', isDark ? 'rgba(255,255,255,0.05)' : 'rgba(0,0,0,0.04)');
	s.setProperty('--bg-active', isDark ? 'rgba(255,255,255,0.08)' : 'rgba(0,0,0,0.07)');
}

function applyTheme(themeId: string): void {
	if (typeof document === 'undefined') return;
	const custom = customThemes.find((t) => t.id === themeId);
	if (custom) {
		applyCustomThemeVars(custom);
	} else {
		document.documentElement.removeAttribute('style');
		document.documentElement.setAttribute('data-theme', themeId);
	}
}

function persistCustomThemes(): void {
	try {
		localStorage.setItem(CUSTOM_STORAGE_KEY, JSON.stringify($state.snapshot(customThemes)));
	} catch { /* ignore */ }
}

export function getThemePreference(): string {
	return preference;
}

export function getResolvedTheme(): string {
	return resolveTheme(preference);
}

export function getCustomThemes(): CustomTheme[] {
	return customThemes;
}

export function setThemePreference(pref: string): void {
	preference = pref;
	localStorage.setItem(STORAGE_KEY, pref);
	applyTheme(resolveTheme(pref));
}

/** Apply a custom theme visually without persisting or updating preference. */
export function previewCustomTheme(theme: CustomTheme): void {
	if (typeof document === 'undefined') return;
	applyCustomThemeVars(theme);
}

export function saveCustomTheme(theme: CustomTheme): void {
	const idx = customThemes.findIndex((t) => t.id === theme.id);
	if (idx >= 0) {
		customThemes[idx] = theme;
		customThemes = [...customThemes];
	} else {
		customThemes = [...customThemes, theme];
	}
	persistCustomThemes();
}

export function deleteCustomTheme(id: string): void {
	customThemes = customThemes.filter((t) => t.id !== id);
	persistCustomThemes();
	if (preference === id) setThemePreference('system');
}

export function initTheme() {
	// State already loaded at module init; just apply visuals + listeners.
	applyTheme(resolveTheme(preference));

	window.matchMedia('(prefers-color-scheme: light)').addEventListener('change', () => {
		if (preference === 'system') {
			applyTheme(getSystemTheme());
		}
	});
}
