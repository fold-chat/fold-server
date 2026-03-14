export type Density = 'compact' | 'default' | 'large';

const STORAGE_KEY = 'fold_density';

let density = $state<Density>('default');

function applyDensity(d: Density) {
	if (typeof document === 'undefined') return;
	if (d === 'default') {
		document.documentElement.removeAttribute('data-density');
	} else {
		document.documentElement.setAttribute('data-density', d);
	}
}

export function getDensity(): Density {
	return density;
}

export function setDensity(d: Density) {
	density = d;
	localStorage.setItem(STORAGE_KEY, d);
	applyDensity(d);
}

export function initDensity() {
	try {
		const stored = localStorage.getItem(STORAGE_KEY);
		if (stored && ['compact', 'default', 'large'].includes(stored)) {
			density = stored as Density;
		}
	} catch { /* ignore */ }
	applyDensity(density);
}
