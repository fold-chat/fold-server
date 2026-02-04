<script lang="ts">
	import { getUser } from '$lib/stores/auth.svelte.js';
	import { openSearch } from '$lib/stores/search.svelte.js';
	import { getTotalMentionCount } from '$lib/stores/channels.svelte.js';
	import { getUnreadNotificationCount, toggleNotificationPanel, isNotificationPanelOpen } from '$lib/stores/notifications.svelte.js';
	import { isMembersPanelOpen, toggleMembersPanel } from '$lib/stores/membersPanel.svelte.js';
	import { isNarrowScreen, toggleSidebar } from '$lib/stores/sidebar.svelte.js';
	import { logout } from '$lib/api/auth.js';
	import { reset } from '$lib/stores/auth.svelte.js';
	import { disconnect } from '$lib/stores/ws.svelte.js';
	import { goto } from '$app/navigation';

	let dropdownOpen = $state(false);

	const user = $derived(getUser());
	const notifCount = $derived(getUnreadNotificationCount());

	function toggleDropdown() {
		dropdownOpen = !dropdownOpen;
	}

	function closeDropdown() {
		dropdownOpen = false;
	}

	function onDropdownKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') closeDropdown();
	}

	async function handleLogout() {
		closeDropdown();
		disconnect();
		try {
			await logout();
		} catch {
			// ignore
		}
		reset();
		goto('/login');
	}

	function goProfile() {
		closeDropdown();
		goto('/settings/profile');
	}
</script>

<div class="topbar">
	{#if isNarrowScreen()}
		<button class="topbar-btn sidebar-toggle" onclick={toggleSidebar} title="Toggle sidebar">
			<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
				<line x1="3" y1="6" x2="21" y2="6" />
				<line x1="3" y1="12" x2="21" y2="12" />
				<line x1="3" y1="18" x2="21" y2="18" />
			</svg>
		</button>
	{/if}
	<div class="topbar-center">
		<button class="search-trigger" onclick={openSearch}>
			<svg viewBox="0 0 20 20" fill="currentColor" width="14" height="14">
				<path fill-rule="evenodd" d="M9 3.5a5.5 5.5 0 100 11 5.5 5.5 0 000-11zM2 9a7 7 0 1112.45 4.39l4.26 4.26a.75.75 0 11-1.06 1.06l-4.26-4.26A7 7 0 012 9z" clip-rule="evenodd" />
			</svg>
			<span class="search-placeholder">Search…</span>
			<kbd class="search-kbd">⌘K</kbd>
		</button>
	</div>

	<div class="topbar-right">
		<button class="topbar-btn" class:active={isNotificationPanelOpen()} onclick={toggleNotificationPanel} title="Notifications">
			<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
				<path d="M18 8A6 6 0 006 8c0 7-3 9-3 9h18s-3-2-3-9" />
				<path d="M13.73 21a2 2 0 01-3.46 0" />
			</svg>
			{#if notifCount > 0}
				<span class="notif-badge">{notifCount > 99 ? '99+' : notifCount}</span>
			{/if}
		</button>

		<button class="topbar-btn" class:active={isMembersPanelOpen()} onclick={toggleMembersPanel} title="Members">
			<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
				<path d="M17 21v-2a4 4 0 00-4-4H5a4 4 0 00-4 4v2" />
				<circle cx="9" cy="7" r="4" />
				<path d="M23 21v-2a4 4 0 00-3-3.87" />
				<path d="M16 3.13a4 4 0 010 7.75" />
			</svg>
		</button>

<button class="topbar-btn" onclick={() => goto('/settings/profile')} title="Settings">
			<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
				<circle cx="12" cy="12" r="3" />
				<path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" />
			</svg>
		</button>

		<div class="user-menu-wrapper">
			<button class="user-btn" onclick={toggleDropdown} title={user?.username ?? ''}>
				{#if user?.avatar_url}
					<img class="user-avatar" src={user.avatar_url} alt="" />
				{:else}
					<span class="user-avatar-placeholder">{(user?.username ?? '?').charAt(0).toUpperCase()}</span>
				{/if}
				<span class="user-name">{user?.display_name || user?.username || ''}</span>
			</button>

			{#if dropdownOpen}
				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<div class="dropdown-overlay" onclick={closeDropdown} onkeydown={onDropdownKeydown}></div>
				<div class="dropdown-menu">
					<button class="dropdown-item" onclick={goProfile}>Edit Profile</button>
					<button class="dropdown-item danger" onclick={handleLogout}>Logout</button>
				</div>
			{/if}
		</div>
	</div>
</div>

<style>
	.topbar {
		height: 48px;
		min-height: 48px;
		display: flex;
		align-items: center;
		padding: 0 1rem;
		background: var(--bg-surface);
		border-bottom: 1px solid var(--border);
		gap: 0.5rem;
		z-index: 10;
	}

	.sidebar-toggle {
		flex-shrink: 0;
	}

	.topbar-center {
		flex: 1;
		display: flex;
		justify-content: center;
		min-width: 0;
	}

	.search-trigger {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-input);
		border: 1px solid var(--border);
		border-radius: 6px;
		padding: 0.4rem 0.75rem;
		color: var(--text-muted);
		cursor: pointer;
		font-size: 0.8rem;
		max-width: 400px;
		width: 100%;
		transition: border-color 0.15s;
	}

	.search-trigger:hover {
		background: var(--bg-input);
		border-color: var(--text-muted);
		color: var(--text);
	}

	.search-placeholder {
		flex: 1;
		text-align: left;
	}

	.search-kbd {
		font-size: 0.65rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 3px;
		padding: 0.1rem 0.35rem;
		color: var(--text-muted);
		font-family: inherit;
	}

	.topbar-right {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		flex-shrink: 0;
	}

	.topbar-btn {
		background: none;
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.35rem;
		border-radius: 4px;
		display: flex;
		align-items: center;
		justify-content: center;
		position: relative;
	}

	.topbar-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	.topbar-btn.active {
		color: var(--accent);
	}

	.notif-badge {
		position: absolute;
		top: 0;
		right: 0;
		background: #e74c3c;
		color: white;
		font-size: 0.55rem;
		font-weight: 700;
		padding: 0.05rem 0.3rem;
		border-radius: 8px;
		min-width: 0.85rem;
		text-align: center;
		line-height: 1.2;
	}

	.user-menu-wrapper {
		position: relative;
	}

	.user-btn {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		background: none;
		border: none;
		color: var(--text);
		cursor: pointer;
		padding: 0.35rem 0.5rem;
		border-radius: 4px;
		font-size: 0.8rem;
	}

	.user-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.user-avatar {
		width: 24px;
		height: 24px;
		border-radius: 50%;
		object-fit: cover;
	}

	.user-avatar-placeholder {
		width: 24px;
		height: 24px;
		border-radius: 50%;
		background: var(--accent);
		color: white;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.7rem;
		font-weight: 600;
	}

	.user-name {
		max-width: 100px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.dropdown-overlay {
		position: fixed;
		inset: 0;
		z-index: 99;
	}

	.dropdown-menu {
		position: absolute;
		top: 100%;
		right: 0;
		margin-top: 0.25rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 6px;
		padding: 0.3rem;
		min-width: 140px;
		box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
		z-index: 100;
	}

	.dropdown-item {
		width: 100%;
		padding: 0.4rem 0.6rem;
		background: none;
		border: none;
		color: var(--text);
		font-size: 0.8rem;
		cursor: pointer;
		border-radius: 4px;
		text-align: left;
	}

	.dropdown-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.08));
	}

	.dropdown-item.danger {
		color: var(--error, #e74c3c);
	}

	.dropdown-item.danger:hover {
		background: rgba(231, 76, 60, 0.15);
	}
</style>
