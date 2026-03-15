<script lang="ts">
	import { page } from '$app/state';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import '$lib/styles/settings.css';

	let { children } = $props();

	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));
	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));
	const canBan = $derived(hasServerPermission(PermissionName.BAN_MEMBERS));
	const canInvite = $derived(
		hasServerPermission(PermissionName.CREATE_INVITES) || hasServerPermission(PermissionName.MANAGE_INVITES)
	);
	const canViewAudit = $derived(
		hasServerPermission(PermissionName.ADMINISTRATOR) || hasServerPermission(PermissionName.MANAGE_SERVER)
	);
	const showServerGroup = $derived(canManageServer || canManageRoles || canBan || canInvite || canViewAudit);

	function isActive(path: string): boolean {
		return page.url.pathname === path;
	}

	// --- Collapsed nav state ---
	let navOpen = $state(false);
	let dropdownEl = $state<HTMLElement | null>(null);
	let triggerEl = $state<HTMLElement | null>(null);

	const PAGE_LABELS: Record<string, string> = {
		'/settings/profile': 'Profile',
		'/settings/appearance': 'Appearance',
		'/settings/devices': 'Devices',
		'/settings/server': 'Server',
		'/settings/roles': 'Roles',
		'/settings/members': 'Members',
		'/settings/invites': 'Invites',
		'/settings/emoji': 'Emoji',
		'/settings/voice': 'Voice',
		'/settings/media': 'Media',
		'/settings/maintenance': 'Maintenance',
		'/settings/audit-log': 'Audit Log'
	};

	const currentLabel = $derived(PAGE_LABELS[page.url.pathname] ?? 'Settings');

	// Close dropdown on route change
	$effect(() => {
		page.url.pathname;
		navOpen = false;
	});

	function toggleNav() {
		navOpen = !navOpen;
	}

	function onDropdownKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			navOpen = false;
			triggerEl?.focus();
		}
	}

	function onClickOutside(e: MouseEvent) {
		if (!navOpen) return;
		const target = e.target as Node;
		if (dropdownEl?.contains(target) || triggerEl?.contains(target)) return;
		navOpen = false;
	}
</script>

<svelte:window onclick={onClickOutside} />

<div class="settings-shell">
	<!-- Sidebar nav (wide mode) -->
	<nav class="settings-nav">
		<a class="nav-back" href="/">&larr; Back</a>
		{@render navItems()}
	</nav>

	<div class="settings-main">
		<!-- Collapsed nav trigger + dropdown (narrow mode) -->
		<div class="nav-collapsed">
			<div class="nav-collapsed-row">
				<a class="nav-back" href="/">&larr; Back</a>
				<button class="nav-trigger" bind:this={triggerEl} onclick={toggleNav}>
					<span>{currentLabel}</span>
					<span class="nav-chevron" class:open={navOpen}>&#9662;</span>
				</button>
			</div>
			{#if navOpen}
				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<div class="nav-dropdown" bind:this={dropdownEl} onkeydown={onDropdownKeydown}>
					{@render navItems()}
				</div>
			{/if}
		</div>

		<div class="settings-content">
			{@render children()}
<div class="powered-by">Powered by <a href="https://fold.chat" target="_blank" rel="noopener">fold.chat</a></div>
		</div>
	</div>
</div>

{#snippet navItems()}
	<div class="nav-group">
		<span class="nav-group-label">User</span>
		<a class="nav-item" class:active={isActive('/settings/profile')} href="/settings/profile">Profile</a>
		<a class="nav-item" class:active={isActive('/settings/appearance')} href="/settings/appearance">Appearance</a>
		<a class="nav-item" class:active={isActive('/settings/devices')} href="/settings/devices">Devices</a>
	</div>

	{#if showServerGroup}
		<div class="nav-group">
			<span class="nav-group-label">Server</span>
			{#if canManageServer}
				<a class="nav-item" class:active={isActive('/settings/server')} href="/settings/server">Server</a>
			{/if}
			{#if canManageRoles}
				<a class="nav-item" class:active={isActive('/settings/roles')} href="/settings/roles">Roles</a>
			{/if}
			{#if canManageRoles || canBan}
				<a class="nav-item" class:active={isActive('/settings/members')} href="/settings/members">Members</a>
			{/if}
			{#if canInvite}
				<a class="nav-item" class:active={isActive('/settings/invites')} href="/settings/invites">Invites</a>
			{/if}
			{#if canManageServer}
				<a class="nav-item" class:active={isActive('/settings/emoji')} href="/settings/emoji">Emoji</a>
			{/if}
			{#if canManageServer}
				<a class="nav-item" class:active={isActive('/settings/voice')} href="/settings/voice">Voice</a>
			{/if}
			{#if canManageServer}
				<a class="nav-item" class:active={isActive('/settings/media')} href="/settings/media">Media</a>
			{/if}
			{#if canManageServer}
				<a class="nav-item" class:active={isActive('/settings/maintenance')} href="/settings/maintenance">Maintenance</a>
			{/if}
			{#if canViewAudit}
				<a class="nav-item" class:active={isActive('/settings/audit-log')} href="/settings/audit-log">Audit Log</a>
			{/if}
		</div>
	{/if}
{/snippet}

<style>
	.settings-shell {
		display: flex;
		height: 100%;
		width: 100%;
		overflow: hidden;
		max-width: 960px;
		margin: 0 auto;
		padding: 2rem 1rem;
		gap: 1.5rem;
		box-sizing: border-box;
		container-type: inline-size;
	}

	/* Wide mode: sidebar visible, collapsed header hidden */
	.settings-nav {
		width: 180px;
		min-width: 180px;
		padding-top: 0.25rem;
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		flex-shrink: 0;
	}

	.settings-main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
		overflow: hidden;
	}

	.nav-collapsed {
		display: none;
		position: relative;
	}

	/* Narrow mode via container query */
	@container (max-width: 700px) {
		.settings-nav {
			display: none;
		}

		.nav-collapsed {
			display: block;
			margin-bottom: 1rem;
		}
	}

	.nav-collapsed-row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}

	.nav-trigger {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text);
		padding: 0.4rem 0.75rem;
		border-radius: 6px;
		font-size: 0.85rem;
		font-weight: 500;
		cursor: pointer;
		font-family: inherit;
	}

	.nav-trigger:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.nav-chevron {
		font-size: 0.7rem;
		transition: transform 150ms ease;
	}

	.nav-chevron.open {
		transform: rotate(180deg);
	}

	.nav-dropdown {
		position: absolute;
		top: 100%;
		left: 0;
		right: 0;
		margin-top: 0.25rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 0.5rem;
		z-index: 20;
		box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
		animation: dropdown-in 150ms ease;
	}

	@keyframes dropdown-in {
		from {
			opacity: 0;
			transform: translateY(-4px);
		}
		to {
			opacity: 1;
			transform: translateY(0);
		}
	}

	.nav-back {
		font-size: 0.8rem;
		color: var(--text-muted);
		text-decoration: none;
		padding: 0.4rem 0.5rem;
		border-radius: 4px;
		margin-bottom: 0.75rem;
	}

	.nav-back:hover {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	/* In collapsed row, remove bottom margin from back link */
	.nav-collapsed-row .nav-back {
		margin-bottom: 0;
	}

	.nav-group {
		display: flex;
		flex-direction: column;
		gap: 0.1rem;
		margin-bottom: 0.5rem;
	}

	.nav-group-label {
		font-size: 0.65rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
		padding: 0.25rem 0.5rem;
	}

	.nav-item {
		font-size: 0.85rem;
		color: var(--text-muted);
		text-decoration: none;
		padding: 0.4rem 0.5rem;
		border-radius: 4px;
	}

	.nav-item:hover {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.nav-item.active {
		color: var(--text);
		background: var(--bg-active, rgba(255, 255, 255, 0.08));
		font-weight: 500;
	}

	.settings-content {
		flex: 1;
		overflow-y: auto;
		min-width: 320px;
	}
</style>
