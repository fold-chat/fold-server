<script lang="ts">
	import { hasServerPermission, arePermissionsLoaded, isMaintenanceEnabled, getMaintenanceMessage } from '$lib/stores/auth.svelte.js';
	import { getMaintenanceStatus, disableServer, enableServer } from '$lib/api/settings.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';
	import '$lib/styles/settings.css';

	const loaded = $derived(arePermissionsLoaded());
	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));

	let maintenanceOn = $state(false);
	let message = $state('');
	let loading = $state(false);
	let error = $state('');
	let success = $state('');
	let statusLoaded = $state(false);

	onMount(async () => {
		try {
			const status = await getMaintenanceStatus();
			maintenanceOn = status.maintenance_enabled;
			message = status.maintenance_message || '';
			statusLoaded = true;
		} catch {
			// Fallback to store state
			maintenanceOn = isMaintenanceEnabled();
			message = getMaintenanceMessage() || '';
			statusLoaded = true;
		}
	});

	// Keep in sync with real-time updates from WS
	$effect(() => {
		if (!statusLoaded) return;
		const wsEnabled = isMaintenanceEnabled();
		const wsMessage = getMaintenanceMessage();
		if (wsEnabled !== maintenanceOn) {
			maintenanceOn = wsEnabled;
			message = wsMessage || '';
		}
	});

	async function handleToggle() {
		loading = true;
		error = '';
		success = '';
		try {
			if (maintenanceOn) {
				await enableServer();
				maintenanceOn = false;
				success = 'Server enabled — maintenance mode off';
			} else {
				await disableServer(message.trim() || undefined);
				maintenanceOn = true;
				success = 'Server disabled — maintenance mode on';
			}
			setTimeout(() => (success = ''), 3000);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to update maintenance status';
		} finally {
			loading = false;
		}
	}

	async function handleUpdateMessage() {
		if (!maintenanceOn) return;
		loading = true;
		error = '';
		success = '';
		try {
			await disableServer(message.trim() || undefined);
			success = 'Maintenance message updated';
			setTimeout(() => (success = ''), 3000);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to update message';
		} finally {
			loading = false;
		}
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Maintenance Mode</h1>
	</div>

	{#if error}
		<div class="error-message">{error}</div>
	{/if}

	{#if success}
		<div class="success-message">{success}</div>
	{/if}

	{#if !loaded || !statusLoaded}
		<p class="muted">Loading…</p>
	{:else if !canManageServer}
		<p class="muted">You don't have permission to manage server settings.</p>
	{:else}
		<div class="form-section">
			<div class="maintenance-status">
				<span class="status-dot" class:active={maintenanceOn}></span>
				<span class="status-text">
					{maintenanceOn ? 'Maintenance mode is ON — non-admin users are blocked' : 'Server is running normally'}
				</span>
			</div>

			<div class="form-group">
				<label for="maintenanceMsg">Maintenance Message</label>
				<textarea
					id="maintenanceMsg"
					bind:value={message}
					maxlength="500"
					rows="3"
					placeholder="Shown to users when the server is disabled (optional)"
				></textarea>
				<span class="char-count">{message.length}/500</span>
			</div>

			<div class="form-actions">
				<button
					class={maintenanceOn ? 'btn-primary' : 'btn-danger-primary'}
					onclick={handleToggle}
					disabled={loading}
				>
					{#if loading}
						Working…
					{:else if maintenanceOn}
						Enable Server
					{:else}
						Disable Server
					{/if}
				</button>

				{#if maintenanceOn}
					<button class="btn-sm" onclick={handleUpdateMessage} disabled={loading}>
						Update Message
					</button>
				{/if}
			</div>

			<p class="muted">
				When disabled, all non-admin API requests return 503. Admins can still access the server normally.
			</p>
		</div>
	{/if}
</div>

<style>
	.maintenance-status {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.75rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 6px;
	}

	.status-dot {
		width: 10px;
		height: 10px;
		border-radius: 50%;
		background: #2ecc71;
		flex-shrink: 0;
	}

	.status-dot.active {
		background: #e74c3c;
	}

	.status-text {
		font-size: 0.875rem;
		color: var(--text);
	}

	.char-count {
		font-size: 0.7rem;
		color: var(--text-muted);
		text-align: right;
	}

	.btn-danger-primary {
		padding: 0.5rem 1rem;
		background: #e74c3c;
		color: white;
		border: none;
		border-radius: 4px;
		font-size: 0.875rem;
		cursor: pointer;
	}

	.btn-danger-primary:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
</style>
