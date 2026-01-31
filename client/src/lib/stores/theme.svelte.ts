export type ThemePreference = 'dark' | 'light' | 'system';
export type ResolvedTheme = 'dark' | 'light';

const STORAGE_KEY = 'kith_theme';

let preference = $state<ThemePreference>('system');

function getSystemTheme(): ResolvedTheme {
	if (typeof window === 'undefined') return 'dark';
	return window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

function resolveTheme(pref: ThemePreference): ResolvedTheme {
	return pref === 'system' ? getSystemTheme() : pref;
}

function applyTheme(theme: ResolvedTheme) {
	if (typeof document === 'undefined') return;
	document.documentElement.setAttribute('data-theme', theme);
}

export function getThemePreference(): ThemePreference {
	return preference;
}

export function getResolvedTheme(): ResolvedTheme {
	return resolveTheme(preference);
}

export function setThemePreference(pref: ThemePreference) {
	preference = pref;
	localStorage.setItem(STORAGE_KEY, pref);
	applyTheme(resolveTheme(pref));
}

export function cycleTheme() {
	const order: ThemePreference[] = ['dark', 'light', 'system'];
	const next = order[(order.indexOf(preference) + 1) % order.length];
	setThemePreference(next);
}

export function initTheme() {
	try {
		const stored = localStorage.getItem(STORAGE_KEY) as ThemePreference | null;
		if (stored && ['dark', 'light', 'system'].includes(stored)) {
			preference = stored;
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
