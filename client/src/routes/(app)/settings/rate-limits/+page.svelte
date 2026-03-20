<script lang="ts">
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { getRuntimeConfig, updateRuntimeConfig, type RuntimeConfig } from '$lib/api/config.js';

	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));

	let config = $state<RuntimeConfig>({});
	let loading = $state(false);
	let saving = $state(false);
	let error = $state('');
	let success = $state('');

	type RateLimitDef = { key: string; label: string; defaultCount: number; defaultWindow: number };

	const AUTH_LIMITS: RateLimitDef[] = [
		{ key: 'fold.rate-limit.login', label: 'Login', defaultCount: 5, defaultWindow: 60 },
		{ key: 'fold.rate-limit.register', label: 'Register', defaultCount: 3, defaultWindow: 3600 },
		{ key: 'fold.rate-limit.refresh', label: 'Token Refresh', defaultCount: 10, defaultWindow: 60 },
		{ key: 'fold.rate-limit.invite-join', label: 'Invite Join', defaultCount: 5, defaultWindow: 60 },
		{ key: 'fold.rate-limit.password-change', label: 'Password Change', defaultCount: 3, defaultWindow: 3600 },
		{ key: 'fold.rate-limit.profile-update', label: 'Profile Update', defaultCount: 5, defaultWindow: 60 }
	];

	const MESSAGE_LIMITS: RateLimitDef[] = [
		{ key: 'fold.rate-limit.message-send', label: 'Send Message', defaultCount: 10, defaultWindow: 10 },
		{ key: 'fold.rate-limit.message-edit', label: 'Edit Message', defaultCount: 5, defaultWindow: 10 },
		{ key: 'fold.rate-limit.message-delete', label: 'Delete Message', defaultCount: 5, defaultWindow: 10 },
		{ key: 'fold.rate-limit.reaction-add', label: 'Add Reaction', defaultCount: 10, defaultWindow: 10 },
		{ key: 'fold.rate-limit.reaction-remove', label: 'Remove Reaction', defaultCount: 10, defaultWindow: 10 }
	];

	const CHANNEL_LIMITS: RateLimitDef[] = [
		{ key: 'fold.rate-limit.thread-create', label: 'Create Thread', defaultCount: 5, defaultWindow: 60 },
		{ key: 'fold.rate-limit.search', label: 'Search', defaultCount: 10, defaultWindow: 60 },
		{ key: 'fold.rate-limit.media-search', label: 'GIF / Sticker Search', defaultCount: 20, defaultWindow: 60 },
		{ key: 'fold.rate-limit.emoji-upload', label: 'Emoji Upload', defaultCount: 5, defaultWindow: 60 },
		{ key: 'fold.rate-limit.emoji-delete', label: 'Emoji Delete', defaultCount: 5, defaultWindow: 60 }
	];

	const VOICE_LIMITS: RateLimitDef[] = [
		{ key: 'fold.rate-limit.voice-token', label: 'Voice Token', defaultCount: 5, defaultWindow: 60 },
		{ key: 'fold.rate-limit.voice-state', label: 'Voice State', defaultCount: 10, defaultWindow: 10 },
		{ key: 'fold.rate-limit.voice-moderation', label: 'Voice Moderation', defaultCount: 10, defaultWindow: 60 }
	];

	const ALL_LIMITS = [...AUTH_LIMITS, ...MESSAGE_LIMITS, ...CHANNEL_LIMITS, ...VOICE_LIMITS];

	// Editable state: key → { count, window }
	function defaultEdits(): Record<string, { count: string; window: string }> {
		const result: Record<string, { count: string; window: string }> = {};
		for (const def of ALL_LIMITS) {
			result[def.key] = { count: String(def.defaultCount), window: String(def.defaultWindow) };
		}
		return result;
	}
	let edits = $state<Record<string, { count: string; window: string }>>(defaultEdits());

	function hydrateFields(cfg: RuntimeConfig) {
		const result: Record<string, { count: string; window: string }> = {};
		for (const def of ALL_LIMITS) {
			const val = cfg[def.key];
			if (val && val.includes('/')) {
				const [c, w] = val.split('/');
				result[def.key] = { count: c, window: w };
			} else {
				result[def.key] = { count: String(def.defaultCount), window: String(def.defaultWindow) };
			}
		}
		edits = result;
	}

	function configValue(key: string, def: RateLimitDef): string {
		const val = config[key];
		if (val && val.includes('/')) return val;
		return `${def.defaultCount}/${def.defaultWindow}`;
	}

	const hasChanges = $derived.by(() => {
		for (const def of ALL_LIMITS) {
			const current = configValue(def.key, def);
			const edited = `${edits[def.key]?.count ?? def.defaultCount}/${edits[def.key]?.window ?? def.defaultWindow}`;
			if (edited !== current) return true;
		}
		return false;
	});

	function formatWindow(seconds: string): string {
		const n = parseInt(seconds);
		if (isNaN(n)) return seconds + 's';
		if (n >= 3600) return (n / 3600) + 'h';
		if (n >= 60) return (n / 60) + 'm';
		return n + 's';
	}

	async function loadAll() {
		loading = true;
		error = '';
		try {
			const cfg = await getRuntimeConfig();
			config = cfg;
			hydrateFields(cfg);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to load rate limit settings';
		} finally {
			loading = false;
		}
	}

	async function save() {
		saving = true;
		error = '';
		success = '';
		try {
			const patch: RuntimeConfig = {};
			for (const def of ALL_LIMITS) {
				const e = edits[def.key];
				if (!e) continue;
				const newVal = `${e.count}/${e.window}`;
				const current = configValue(def.key, def);
				if (newVal !== current) {
					patch[def.key] = newVal;
				}
			}
			if (Object.keys(patch).length === 0) return;
			const result = await updateRuntimeConfig(patch);
			config = result;
			hydrateFields(result);
			success = 'Settings saved';
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save';
		} finally {
			saving = false;
			setTimeout(() => { success = ''; }, 3000);
		}
	}

	$effect(() => {
		if (canManageServer) loadAll();
	});
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Rate Limits</h1>
		<button class="btn-sm" onclick={loadAll} disabled={loading}>
			{loading ? 'Loading...' : 'Refresh'}
		</button>
	</div>

	{#if !canManageServer}
		<p class="muted">You don't have permission to manage rate limits.</p>
	{:else}
		{#if error}
			<div class="error-message">{error}</div>
		{/if}
		{#if success}
			<div class="success-message">{success}</div>
		{/if}

		<p class="muted" style="margin-bottom: 1rem">
			Format: <strong>count</strong> requests per <strong>window</strong> (seconds). Changes take effect immediately.
		</p>

		{@render section('Authentication', AUTH_LIMITS)}
		{@render section('Messaging', MESSAGE_LIMITS)}
		{@render section('Channels & Content', CHANNEL_LIMITS)}
		{@render section('Voice', VOICE_LIMITS)}

		{#if hasChanges}
			<div class="form-actions" style="margin-top: 1rem">
				<button class="btn-primary" onclick={save} disabled={saving}>
					{saving ? 'Saving...' : 'Save'}
				</button>
			</div>
		{/if}
	{/if}
</div>

{#snippet section(title: string, limits: RateLimitDef[])}
	<div class="form-section" style="margin-top: 1rem">
		<h3 class="subsection">{title}</h3>
		<div class="rate-grid">
			{#each limits as def}
				<span class="rate-label">{def.label}</span>
				<div class="rate-inputs">
					<input
						type="number"
						min="1"
						bind:value={edits[def.key].count}
						class="rate-input"
						aria-label="{def.label} count"
					/>
					<span class="rate-sep">/</span>
					<input
						type="number"
						min="1"
						bind:value={edits[def.key].window}
						class="rate-input"
						aria-label="{def.label} window"
					/>
					<span class="rate-unit">{formatWindow(edits[def.key]?.window ?? String(def.defaultWindow))}</span>
				</div>
				<span class="rate-default">default: {def.defaultCount}/{def.defaultWindow}</span>
			{/each}
		</div>
	</div>
{/snippet}

<style>
	.subsection {
		font-size: 0.85rem;
		margin: 0 0 0.35rem;
		color: var(--text-muted);
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.03em;
	}

	.rate-grid {
		display: grid;
		grid-template-columns: 1fr auto auto;
		align-items: center;
		gap: 0.25rem 0.75rem;
	}

	.rate-grid > .rate-label {
		padding: 0.4rem 0;
		border-bottom: 1px solid var(--border);
	}

	.rate-grid > .rate-inputs {
		padding: 0.4rem 0;
		border-bottom: 1px solid var(--border);
	}

	.rate-grid > .rate-default {
		padding: 0.4rem 0;
		border-bottom: 1px solid var(--border);
	}

	.rate-label {
		font-size: 0.85rem;
	}

	.rate-inputs {
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}

	.rate-input {
		width: 70px;
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.35rem 0.5rem;
		font-size: 0.8rem;
		font-family: inherit;
		text-align: center;
	}

	.rate-sep {
		color: var(--text-muted);
		font-size: 0.85rem;
	}

	.rate-unit {
		font-size: 0.75rem;
		color: var(--text-muted);
		min-width: 24px;
	}

	.rate-default {
		font-size: 0.7rem;
		color: var(--text-muted);
		opacity: 0.6;
		white-space: nowrap;
	}
</style>
