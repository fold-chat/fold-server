<script lang="ts">
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { getVoiceStats, rotateKey, type VoiceStats, type VoiceRoomStats } from '$lib/api/voice.js';
	import { isVoiceVideoEnabled } from '$lib/stores/voice.svelte.js';
	import { getChannels } from '$lib/stores/channels.svelte.js';

	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));

	let voiceStats = $state<VoiceStats | null>(null);
	let voiceLoading = $state(false);
	let voiceError = $state('');
	let rotatingKey = $state<string | null>(null);

	const voiceChannels = $derived(getChannels().filter(c => c.type === 'VOICE'));

	async function loadVoiceStats() {
		voiceLoading = true;
		voiceError = '';
		try {
			voiceStats = await getVoiceStats();
		} catch (err) {
			voiceError = (err as ApiError).message || 'Failed to load voice stats';
		} finally {
			voiceLoading = false;
		}
	}

	async function handleRotateKey(channelId: string) {
		rotatingKey = channelId;
		try {
			await rotateKey(channelId);
		} catch (err) {
			voiceError = (err as ApiError).message || 'Failed to rotate key';
		} finally {
			rotatingKey = null;
		}
	}

	$effect(() => {
		if (isVoiceVideoEnabled() && canManageServer) loadVoiceStats();
	});
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Voice</h1>
		<button class="btn-sm" onclick={loadVoiceStats} disabled={voiceLoading}>
			{voiceLoading ? 'Loading...' : 'Refresh'}
		</button>
	</div>

	{#if !canManageServer}
		<p class="muted">You don't have permission to manage voice settings.</p>
	{:else if !isVoiceVideoEnabled()}
		<p class="muted">Voice is not enabled on this server.</p>
	{:else}
		{#if voiceError}
			<div class="error-message">{voiceError}</div>
		{/if}

		{#if voiceStats}
			<div class="voice-stats">
				<div class="stat-row">
					<span class="stat-label">Mode</span>
					<span class="stat-value">{voiceStats.mode}</span>
				</div>
				<div class="stat-row">
					<span class="stat-label">Status</span>
					<span class="stat-value" class:stat-up={voiceStats.status === 'UP'} class:stat-off={voiceStats.status === 'OFF'}>{voiceStats.status}</span>
				</div>
				<div class="stat-row">
					<span class="stat-label">Active Connections</span>
					<span class="stat-value">{voiceStats.active_connections}</span>
				</div>
				<div class="stat-row">
					<span class="stat-label">Active Rooms</span>
					<span class="stat-value">{voiceStats.active_rooms}</span>
				</div>

				{#if voiceStats.rooms.length > 0}
					<h3 class="subsection">Active Rooms</h3>
					{#each voiceStats.rooms as room}
						{@const ch = getChannels().find(c => c.id === room.channel_id)}
						<div class="stat-row">
							<span class="stat-label">🔊 {ch?.name ?? room.room_name}</span>
							<span class="stat-value">{room.participants} participants</span>
						</div>
					{/each}
				{/if}
			</div>
		{:else if !voiceLoading}
			<p class="muted">No stats loaded yet.</p>
		{/if}

		{#if voiceChannels.length > 0}
			<h3 class="subsection">E2EE Key Rotation</h3>
			<p class="muted" style="margin-bottom: 0.5rem">Force-rotate the encryption key for a voice channel. All connected users will receive the new key automatically.</p>
			<div class="key-rotation-list">
				{#each voiceChannels as vc}
					<div class="key-rotation-row">
						<span class="key-channel-name">🔊 {vc.name}</span>
						<button class="btn-sm" onclick={() => handleRotateKey(vc.id)} disabled={rotatingKey === vc.id}>
							{rotatingKey === vc.id ? 'Rotating...' : 'Rotate Key'}
						</button>
					</div>
				{/each}
			</div>
		{/if}
	{/if}
</div>

<style>
	.voice-stats {
		display: flex;
		flex-direction: column;
		gap: 0.3rem;
	}

	.stat-row {
		display: flex;
		justify-content: space-between;
		padding: 0.35rem 0;
		border-bottom: 1px solid var(--border);
		font-size: 0.85rem;
	}

	.stat-label {
		color: var(--text-muted);
	}

	.stat-value {
		color: var(--text);
		font-weight: 500;
	}

	.stat-up {
		color: #2ecc71;
	}

	.stat-off {
		color: var(--text-muted);
	}

	.subsection {
		font-size: 0.85rem;
		margin: 1rem 0 0.35rem;
		color: var(--text-muted);
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.03em;
	}

	.key-rotation-list {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}

	.key-rotation-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.4rem 0.5rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 4px;
	}

	.key-channel-name {
		font-size: 0.85rem;
	}
</style>
