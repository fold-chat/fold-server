<script lang="ts">
	import { createInvite } from '$lib/api/invites.js';
	import type { ApiError } from '$lib/api/client.js';

	let {
		showMaxUses = true,
		oncreated
	}: {
		showMaxUses?: boolean;
		oncreated?: (code: string) => void;
	} = $props();

	let description = $state('');
	let maxUses = $state<number | undefined>(undefined);
	let expiresIn = $state('never');
	let creating = $state(false);
	let error = $state('');
	let success = $state('');
	let createdCode = $state('');

	const EXPIRY_OPTIONS = [
		{ label: 'Never', value: 'never' },
		{ label: '1 hour', value: '1h' },
		{ label: '24 hours', value: '24h' },
		{ label: '7 days', value: '7d' },
		{ label: '30 days', value: '30d' }
	];

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
			const invite = await createInvite(data);
			createdCode = invite.code;
			success = 'Invite created!';
			oncreated?.(invite.code);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to create invite';
		} finally {
			creating = false;
		}
	}

	async function copyLink() {
		if (!createdCode) return;
		const url = `${window.location.origin}/invite/${createdCode}`;
		await navigator.clipboard.writeText(url);
		success = 'Link copied!';
		setTimeout(() => { success = ''; }, 2000);
	}
</script>

{#if error}
	<div class="error-message">{error}</div>
{/if}
{#if success}
	<div class="success-message">{success}</div>
{/if}

<div class="create-form">
	<div class="form-group form-group-desc">
		<label for="invite-description">Description <span class="required">*</span></label>
		<input id="invite-description" type="text" placeholder="e.g. Facebook Group" bind:value={description} required />
	</div>
	{#if showMaxUses}
		<div class="form-group">
			<label for="invite-max-uses">Max Uses</label>
			<input id="invite-max-uses" type="number" min="1" placeholder="Unlimited" bind:value={maxUses} />
		</div>
	{/if}
	<div class="form-group">
		<label for="invite-expires-in">Expires</label>
		<select id="invite-expires-in" bind:value={expiresIn}>
			{#each EXPIRY_OPTIONS as opt}
				<option value={opt.value}>{opt.label}</option>
			{/each}
		</select>
	</div>
	<div class="form-group">
		<!-- svelte-ignore a11y_label_has_associated_control -->
		<label>&nbsp;</label>
		<button class="btn-primary" onclick={handleCreate} disabled={creating || !description.trim()}>
			{creating ? 'Creating...' : 'Create'}
		</button>
	</div>
</div>

{#if createdCode}
	<div class="created-invite">
		<code class="invite-code">{window.location.origin}/invite/{createdCode}</code>
		<button class="btn-sm" onclick={copyLink}>Copy Link</button>
	</div>
{/if}

<style>
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

	.created-invite {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		margin-top: 1rem;
		padding: 0.6rem 0.75rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 6px;
	}

	.invite-code {
		font-size: 0.8rem;
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
