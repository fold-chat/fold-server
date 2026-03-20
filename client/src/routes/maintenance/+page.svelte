<script lang="ts">
import { onMount } from 'svelte';
	import { goto } from '$app/navigation';
	import { getMe } from '$lib/api/users.js';
	import { setUser } from '$lib/stores/auth.svelte.js';
	import ServerBranding from '$lib/components/ServerBranding.svelte';

	let message = $state('');
	let restartRequired = $state(false);
	let loading = $state(true);
	let checking = $state(false);

	async function checkStatus() {
		try {
			const res = await fetch('/api/v0/status', {
				headers: { 'Content-Type': 'application/json' }
			});
			const data = await res.json();
			if (data.restart_required) {
				restartRequired = true;
				message = data.message || 'Backup restored. Please restart the server to continue.';
			} else if (data.maintenance) {
				restartRequired = false;
				message = data.maintenance_message || '';
			} else {
				// Server is back up
				try {
					const user = await getMe();
					setUser(user);
				} catch {}
				goto('/');
				return;
			}
		} catch {
			message = 'Unable to reach server.';
		} finally {
			loading = false;
		}
	}

	onMount(() => {
		checkStatus();
		// Poll every 15s to check if maintenance is over
		const interval = setInterval(checkStatus, 15000);
		return () => clearInterval(interval);
	});

	async function handleRetry() {
		checking = true;
		await checkStatus();
		checking = false;
	}
</script>

<div class="maintenance-page">
	<div class="maintenance-card">
		{#if loading}
			<p class="muted">Checking server status…</p>
		{:else}
			<ServerBranding />
			<h2>{restartRequired ? 'Restart Required' : 'Under Maintenance'}</h2>
			{#if message}
				<p class="maintenance-message">{message}</p>
			{:else}
				<p class="muted">The server is temporarily unavailable. Please check back soon.</p>
			{/if}
			{#if restartRequired}
				<p class="muted">This page will automatically redirect once the server is back online.</p>
			{/if}
			<button onclick={handleRetry} disabled={checking}>
				{checking ? 'Checking…' : 'Check Again'}
			</button>
		{/if}
	</div>
</div>

<style>
	.maintenance-page {
		display: flex;
		align-items: center;
		justify-content: center;
		min-height: 100vh;
		background: var(--bg, #1a1a2e);
		padding: 1rem;
	}

	.maintenance-card {
		background: var(--bg-surface, #16213e);
		border: 1px solid var(--border, #333);
		border-radius: 12px;
		padding: 2.5rem;
		max-width: 440px;
		width: 100%;
		text-align: center;
	}

	h1 {
		font-size: 1.5rem;
		margin: 0 0 0.25rem;
		color: var(--text, #e0e0e0);
	}

	h2 {
		font-size: 1.1rem;
		font-weight: 400;
		margin: 0 0 1.25rem;
		color: var(--text-muted, #888);
	}

	.maintenance-message {
		font-size: 0.925rem;
		color: var(--text, #e0e0e0);
		line-height: 1.5;
		margin: 0 0 1.5rem;
		padding: 0.75rem;
		background: var(--bg, rgba(255, 255, 255, 0.03));
		border-radius: 6px;
		border: 1px solid var(--border, #333);
	}

	.muted {
		color: var(--text-muted, #888);
		font-size: 0.875rem;
		margin: 0 0 1.5rem;
	}

	button {
		padding: 0.5rem 1.25rem;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 4px;
		font-size: 0.875rem;
		cursor: pointer;
	}

	button:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
</style>
