const NARROW_BREAKPOINT = '(max-width: 768px)';

let narrow = $state(false);
let expanded = $state(true);

if (typeof window !== 'undefined') {
	const mql = window.matchMedia(NARROW_BREAKPOINT);
	narrow = mql.matches;
	if (narrow) expanded = false;
	mql.addEventListener('change', (e) => {
		narrow = e.matches;
		if (!narrow) expanded = true;
	});
}

/** True when viewport is narrow (<=768px) */
export function isNarrowScreen(): boolean {
	return narrow;
}

/** True when sidebar should show full content (always true on wide, toggled on narrow) */
export function isSidebarExpanded(): boolean {
	return !narrow || expanded;
}

/** True when sidebar should show icon rail (narrow + not expanded) */
export function isSidebarCollapsed(): boolean {
	return narrow && !expanded;
}

export function toggleSidebar() {
	expanded = !expanded;
}

export function closeSidebar() {
	expanded = false;
}
