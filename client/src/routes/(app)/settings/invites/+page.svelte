<script lang="ts">
import { createInvite, getInvites, revokeInvite, reinstateInvite, type Invite } from '$lib/api/invites.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { getMembers } from '$lib/stores/members.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';

	let invites = $state<Invite[]>([]);
	let loading = $state(true);
	let error = $state('');
	let success = $state('');
	let creating = $state(false);

	// Create form
	let description = $state('');
	let maxUses = $state<number | undefined>(undefined);
	let expiresIn = $state('never');

	const canCreate = $derived(hasServerPermission(PermissionName.CREATE_INVITES));
	const canManage = $derived(hasServerPermission(PermissionName.MANAGE_INVITES));

	const EXPIRY_OPTIONS = [
		{ label: 'Never', value: 'never' },
		{ label: '1 hour', value: '1h' },
		{ label: '24 hours', value: '24h' },
		{ label: '7 days', value: '7d' },
		{ label: '30 days', value: '30d' }
	];

	onMount(async () => {
		await loadInvites();
	});

	async function loadInvites() {
		try {
			invites = await getInvites();
		} catch {
			error = 'Failed to load invites';
		} finally {
			loading = false;
		}
	}

	function computeExpiresAt(): string | undefined {
		if (expiresIn === 'never') return undefined;
		const now = Date.now();
		const ms: Record<string, number> = {
			'1h': 3600_000,
			'24h': 86400_000,
			'7d': 604800_000,
			'30d': 2592000_000
		};
		return new Date(now + ms[expiresIn]).toISOString();
	}

	async function handleCreate() {
		error = '';
		success = '';
		creating = true;
		try {
		const data: { description: string; max_uses?: number; expires_at?: string } = { description };
			if (maxUses && maxUses > 0) data.max_uses = maxUses;
			const exp = computeExpiresAt();
			if (exp) data.expires_at = exp;
			await createInvite(data);
			description = '';
			maxUses = undefined;
			expiresIn = 'never';
			await loadInvites();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to create invite';
		} finally {
			creating = false;
		}
	}

	async function handleRevoke(code: string) {
		error = '';
		success = '';
		try {
			const updated = await revokeInvite(code);
			invites = invites.map((i) => (i.code === code ? updated : i));
			success = 'Invite revoked';
		} catch (err) {
			error = (err as ApiError).message || 'Failed to revoke invite';
		}
	}

	async function handleReinstate(code: string) {
		error = '';
		success = '';
		try {
			const updated = await reinstateInvite(code);
			invites = invites.map((i) => (i.code === code ? updated : i));
			success = 'Invite reinstated';
		} catch (err) {
			error = (err as ApiError).message || 'Failed to reinstate invite';
		}
	}

	async function copyLink(code: string) {
		const url = `${window.location.origin}/invite/${code}`;
		await navigator.clipboard.writeText(url);
		success = 'Link copied!';
		setTimeout(() => (success = ''), 2000);
	}

	function formatDate(iso: string): string {
		return new Date(iso + (iso.endsWith('Z') ? '' : 'Z')).toLocaleDateString(undefined, {
			month: 'short',
			day: 'numeric',
			year: 'numeric'
		});
	}

	function creatorName(creatorId: string): string {
		const member = getMembers().find(m => m.id === creatorId);
		return member?.display_name || member?.username || 'Unknown';
	}

	function formatExpiry(iso: string | null): string {
		if (!iso) return 'Never';
		const d = new Date(iso + (iso.endsWith('Z') ? '' : 'Z'));
		if (d.getTime() < Date.now()) return 'Expired';
		return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Invites</h1>
	</div>

	{#if error}
		<div class="error-message">{error}</div>
	{/if}
	{#if success}
		<div class="success-message">{success}</div>
	{/if}

	{#if canCreate}
		<div class="create-section">
			<h2>Create Invite</h2>
			<div class="create-form">
				<div class="form-group form-group-desc">
					<label for="description">Description <span class="required">*</span></label>
					<input id="description" type="text" placeholder="e.g. Facebook Group" bind:value={description} required />
				</div>
				{#if canManage}
					<div class="form-group">
						<label for="max-uses">Max Uses</label>
						<input id="max-uses" type="number" min="1" placeholder="Unlimited" bind:value={maxUses} />
					</div>
				{/if}
				<div class="form-group">
					<label for="expires-in">Expires</label>
					<select id="expires-in" bind:value={expiresIn}>
						{#each EXPIRY_OPTIONS as opt}
							<option value={opt.value}>{opt.label}</option>
						{/each}
					</select>
				</div>
				<div class="form-group">
					<label>&nbsp;</label>
				<button class="btn-primary" onclick={handleCreate} disabled={creating || !description.trim()}>
						{creating ? 'Creating...' : 'Create'}
					</button>
				</div>
			</div>
		</div>
	{/if}

	{#if loading}
		<p class="muted">Loading...</p>
	{:else if invites.length === 0}
		<p class="muted">No invites.</p>
	{:else}
		<div class="invite-list">
			{#each invites as invite}
				{@const revoked = invite.revoked_at != null}
				<div class="invite-item" class:revoked>
					<div class="invite-info">
					<span class="invite-code">
						{invite.code}
						{#if revoked}<span class="revoked-badge">Revoked</span>{/if}
					</span>
					{#if invite.description}<span class="invite-desc">{invite.description}</span>{/if}
					<span class="invite-meta">
							{invite.use_count}{invite.max_uses != null ? `/${invite.max_uses}` : ''} uses
							&middot; Expires: {formatExpiry(invite.expires_at)}
							&middot; By {creatorName(invite.creator_id)}
							&middot; {formatDate(invite.created_at)}
						</span>
					</div>
					<div class="invite-actions">
						{#if !revoked}
							<button class="btn-sm" onclick={() => copyLink(invite.code)}>Copy Link</button>
						{/if}
						{#if canManage}
							{#if revoked}
								<button class="btn-sm" onclick={() => handleReinstate(invite.code)}>Reinstate</button>
							{:else}
								<button class="btn-sm btn-danger" onclick={() => handleRevoke(invite.code)}>Revoke</button>
							{/if}
						{/if}
					</div>
				</div>
			{/each}
		</div>
	{/if}
</div>

<style>
	.create-section {
		margin-bottom: 1.5rem;
		padding-bottom: 1rem;
		border-bottom: 1px solid var(--border);
	}

	.create-section h2 {
		font-size: 0.9rem;
		font-weight: 600;
		margin: 0 0 0.75rem;
	}

	.create-form {
		display: flex;
		align-items: flex-end;
		gap: 0.75rem;
		flex-wrap: wrap;
	}

	.create-form .form-group {
		display: flex;
		flex-direction: column;
		gap: 0.3rem;
	}

	.create-form label {
		font-size: 0.7rem;
		font-weight: 600;
		color: var(--text-muted);
		text-transform: uppercase;
		letter-spacing: 0.03em;
		line-height: 1;
	}

	.create-form input,
	.create-form select {
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.5rem 0.6rem;
		font-size: 0.875rem;
		font-family: inherit;
		width: 140px;
		box-sizing: border-box;
		height: 2.125rem;
	}

	.form-group-desc input {
		width: 220px;
	}

	.required {
		color: #e74c3c;
	}

	.create-form .btn-primary {
		height: 2.125rem;
		box-sizing: border-box;
	}

	.invite-list {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.invite-item {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.5rem 0.75rem;
		border-radius: 4px;
		gap: 0.75rem;
	}

	.invite-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.03));
	}

	.invite-info {
		display: flex;
		flex-direction: column;
		gap: 0.15rem;
		min-width: 0;
	}

	.invite-code {
		font-family: monospace;
		font-size: 0.9rem;
		font-weight: 600;
	}

	.invite-desc {
		font-size: 0.8rem;
		color: var(--text-muted);
	}

	.invite-meta {
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.invite-actions {
		display: flex;
		gap: 0.35rem;
		flex-shrink: 0;
	}

	.invite-item.revoked {
		opacity: 0.5;
	}

	.invite-item.revoked:hover {
		opacity: 0.7;
	}

	.revoked-badge {
		font-size: 0.65rem;
		color: #e74c3c;
		background: rgba(231, 76, 60, 0.15);
		padding: 0.05rem 0.3rem;
		border-radius: 3px;
		margin-left: 0.35rem;
		font-weight: 500;
		font-family: inherit;
	}
</style>
