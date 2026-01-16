<script lang="ts">
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import { getRolesList } from '$lib/stores/roles.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { getChannelPermissions, updateChannelPermission, deleteChannelPermission, type ChannelPermissionOverride } from '$lib/api/roles.js';
	import { PermissionName, PERMISSION_GROUPS, isServerLevelPermission } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';

	let channelId = $derived(page.params.id!);
	let overrides = $state<ChannelPermissionOverride[]>([]);
	let loading = $state(true);
	let saving = $state(false);
	let error = $state('');
	let selectedRoleId = $state<string | null>(null);

	const canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));

	// Channel-level permissions only (hide server-level from override UI)
	const channelPerms = [...PERMISSION_GROUPS.Channel, ...PERMISSION_GROUPS.Voice].filter(
		(p) => !isServerLevelPermission(p.name)
	);

	onMount(() => loadOverrides());

	async function loadOverrides() {
		loading = true;
		try {
			overrides = await getChannelPermissions(channelId);
		} catch {
			error = 'Failed to load permissions';
		} finally {
			loading = false;
		}
	}

	function getOverrideForRole(roleId: string): ChannelPermissionOverride | undefined {
		return overrides.find((o) => o.role_id === roleId);
	}

	type TriState = 'allow' | 'deny' | 'inherit';

	function getPermState(roleId: string, perm: string): TriState {
		const o = getOverrideForRole(roleId);
		if (!o) return 'inherit';
		if (o.allow.includes(perm)) return 'allow';
		if (o.deny.includes(perm)) return 'deny';
		return 'inherit';
	}

	function cycleState(current: TriState): TriState {
		if (current === 'inherit') return 'allow';
		if (current === 'allow') return 'deny';
		return 'inherit';
	}

	async function togglePerm(roleId: string, perm: string) {
		if (!canManageChannels) return;
		const current = getPermState(roleId, perm);
		const next = cycleState(current);

		// Build new allow/deny lists
		const existing = getOverrideForRole(roleId);
		let allow = existing ? [...existing.allow] : [];
		let deny = existing ? [...existing.deny] : [];

		// Remove from both
		allow = allow.filter((p) => p !== perm);
		deny = deny.filter((p) => p !== perm);

		// Add to target
		if (next === 'allow') allow.push(perm);
		else if (next === 'deny') deny.push(perm);

		// If both empty, delete override
		if (allow.length === 0 && deny.length === 0 && existing) {
			saving = true;
			try {
				await deleteChannelPermission(channelId, roleId);
				await loadOverrides();
			} catch (err) {
				error = (err as ApiError).message || 'Failed to save';
			} finally {
				saving = false;
			}
			return;
		}

		saving = true;
		try {
			await updateChannelPermission(channelId, roleId, { allow, deny });
			await loadOverrides();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save';
		} finally {
			saving = false;
		}
	}
</script>

<div class="settings-page">
	<div class="settings-card">
		<div class="header-row">
			<h1>Channel Permissions</h1>
			<p><a href="/channels/{channelId}">&larr; Back to channel</a></p>
		</div>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		{#if !canManageChannels}
			<p class="muted">You don't have permission to manage channel permissions.</p>
		{/if}

		{#if loading}
			<p class="muted">Loading...</p>
		{:else}
			<!-- Role selector tabs -->
			<div class="role-tabs">
				{#each getRolesList() as role}
					{#if role.id !== 'owner'}
						<button
							class="role-tab"
							class:active={selectedRoleId === role.id}
							onclick={() => (selectedRoleId = role.id)}
						>
							<span class="role-dot" style="background: {role.color || '#99aab5'}"></span>
							{role.name}
						</button>
					{/if}
				{/each}
			</div>

			{#if selectedRoleId}
				<div class="perm-matrix">
					{#each channelPerms as perm}
						{@const state = getPermState(selectedRoleId, perm.name)}
						<div class="perm-row">
							<div class="perm-info">
								<span class="perm-label">{perm.label}</span>
								<span class="perm-desc">{perm.desc}</span>
							</div>
							<button
								class="tri-toggle {state}"
								onclick={() => selectedRoleId && togglePerm(selectedRoleId, perm.name)}
								disabled={!canManageChannels || saving}
								title="{state === 'inherit' ? 'Inherit' : state === 'allow' ? 'Allow' : 'Deny'} — click to cycle"
							>
								{#if state === 'allow'}
									✓ Allow
								{:else if state === 'deny'}
									✕ Deny
								{:else}
									— Inherit
								{/if}
							</button>
						</div>
					{/each}
				</div>
			{:else}
				<p class="muted">Select a role to configure channel permission overrides.</p>
			{/if}
		{/if}
	</div>
</div>

<style>
	.settings-page {
		padding: 2rem;
		max-width: 800px;
		margin: 0 auto;
		overflow-y: auto;
		height: 100vh;
	}

	.settings-card {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 1.5rem;
	}

	.header-row {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		margin-bottom: 1rem;
	}

	h1 {
		font-size: 1.25rem;
		margin: 0;
	}

	.muted {
		color: var(--text-muted);
		font-size: 0.875rem;
	}

	.role-tabs {
		display: flex;
		gap: 0.35rem;
		flex-wrap: wrap;
		margin-bottom: 1rem;
	}

	.role-tab {
		display: inline-flex;
		align-items: center;
		gap: 0.35rem;
		padding: 0.35rem 0.6rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 4px;
		font-size: 0.8rem;
		cursor: pointer;
	}

	.role-tab:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	.role-tab.active {
		background: var(--bg-active, rgba(255, 255, 255, 0.08));
		color: var(--text);
		border-color: var(--accent, #5865f2);
	}

	.role-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
	}

	.perm-matrix {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.perm-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.4rem 0.5rem;
		border-radius: 4px;
	}

	.perm-row:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.02));
	}

	.perm-info {
		display: flex;
		flex-direction: column;
	}

	.perm-label {
		font-size: 0.85rem;
		font-weight: 500;
	}

	.perm-desc {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.tri-toggle {
		padding: 0.25rem 0.6rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		font-size: 0.75rem;
		cursor: pointer;
		min-width: 80px;
		text-align: center;
		background: none;
		color: var(--text-muted);
	}

	.tri-toggle.allow {
		background: rgba(46, 204, 113, 0.15);
		border-color: #2ecc71;
		color: #2ecc71;
	}

	.tri-toggle.deny {
		background: rgba(231, 76, 60, 0.15);
		border-color: #e74c3c;
		color: #e74c3c;
	}

	.tri-toggle.inherit {
		background: none;
		color: var(--text-muted);
	}

	.tri-toggle:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.error-message {
		color: #e74c3c;
		font-size: 0.85rem;
		margin-bottom: 0.75rem;
		padding: 0.5rem;
		background: rgba(231, 76, 60, 0.1);
		border-radius: 4px;
	}
</style>
