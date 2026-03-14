import { closeNotificationPanel } from '$lib/stores/notifications.svelte.js';

const STORAGE_KEY = 'fold_members_panel_open';

function loadState(): boolean {
	try {
		return localStorage.getItem(STORAGE_KEY) === 'true';
	} catch {
		return false;
	}
}

let open = $state(loadState());
let pendingUserId = $state<string | null>(null);

export function isMembersPanelOpen(): boolean {
	return open;
}

export function toggleMembersPanel() {
	const next = !open;
	open = next;
	if (!next) pendingUserId = null;
	try { localStorage.setItem(STORAGE_KEY, String(open)); } catch { /* ignore */ }
	if (next) closeNotificationPanel();
}

export function closeMembersPanel() {
	open = false;
	pendingUserId = null;
	try { localStorage.setItem(STORAGE_KEY, 'false'); } catch { /* ignore */ }
}

/** Open the members panel and select a specific user's profile */
export function openMemberProfile(userId: string) {
	pendingUserId = userId;
	if (!open) {
		open = true;
		try { localStorage.setItem(STORAGE_KEY, 'true'); } catch { /* ignore */ }
		closeNotificationPanel();
	}
}

export function getPendingUserId(): string | null {
	return pendingUserId;
}

export function clearPendingUserId() {
	pendingUserId = null;
}
