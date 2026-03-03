<script lang="ts">
	import { page } from '$app/state';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { isVoiceVideoEnabled } from '$lib/stores/voice.svelte.js';
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
	const canVoice = $derived(canManageServer && isVoiceVideoEnabled());
	const showServerGroup = $derived(canManageServer || canManageRoles || canBan || canInvite || canViewAudit || canVoice);

	function isActive(path: string): boolean {
		return page.url.pathname === path;
	}
</script>

<div class="settings-shell">
	<nav class="settings-nav">
		<a class="nav-back" href="/">&larr; Back</a>

		<div class="nav-group">
			<span class="nav-group-label">User</span>
			<a class="nav-item" class:active={isActive('/settings/profile')} href="/settings/profile">Profile</a>
			<a class="nav-item" class:active={isActive('/settings/appearance')} href="/settings/appearance">Appearance</a>
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
				{#if canVoice}
					<a class="nav-item" class:active={isActive('/settings/voice')} href="/settings/voice">Voice</a>
				{/if}
				{#if canManageServer}
					<a class="nav-item" class:active={isActive('/settings/maintenance')} href="/settings/maintenance">Maintenance</a>
				{/if}
				{#if canViewAudit}
					<a class="nav-item" class:active={isActive('/settings/audit-log')} href="/settings/audit-log">Audit Log</a>
				{/if}
			</div>
		{/if}
	</nav>

	<div class="settings-content">
		{@render children()}
	</div>
</div>

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
	}

	.settings-nav {
		width: 180px;
		min-width: 180px;
		padding-top: 0.25rem;
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		flex-shrink: 0;
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
		min-width: 0;
	}
</style>
