<script lang="ts">
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { getVoiceStats, rotateKey, type VoiceStats } from '$lib/api/voice.js';
	import { getVoiceMode, isE2eeCapability } from '$lib/stores/voice.svelte.js';
	import { getChannels } from '$lib/stores/channels.svelte.js';
	import VoiceConfig from '$lib/components/settings/VoiceConfig.svelte';

	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));
	const mode = $derived(getVoiceMode());
	const isEnabled = $derived(mode !== 'off');

	let voiceStats = $state<VoiceStats | null>(null);
	let error = $state('');
	let rotatingKey = $state<string | null>(null);

	const voiceChannels = $derived(getChannels().filter(c => c.type === 'VOICE'));

	async function loadStats() {
		try {
			voiceStats = await getVoiceStats();
		} catch { /* ignore */ }
	}

	async function handleRotateKey(channelId: string) {
		rotatingKey = channelId;
		try {
			await rotateKey(channelId);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to rotate key';
		} finally {
			rotatingKey = null;
		}
	}

	$effect(() => {
		if (canManageServer) loadStats();
	});
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Voice & Video</h1>
	</div>

	{#if !canManageServer}
		<p class="muted">You don't have permission to manage voice settings.</p>
	{:else}
		<VoiceConfig onloaded={loadStats} />

		<!-- Stats (when active) -->
		{#if isEnabled && voiceStats}
			<div class="form-section" style="margin-top: 1rem">
				<h3 class="subsection">Stats</h3>
				<div class="voice-stats">
					<div class="stat-row">
						<span class="stat-label">Active Connections</span>
						<span class="stat-value">{voiceStats.active_connections}</span>
					</div>
					<div class="stat-row">
						<span class="stat-label">Active Rooms</span>
						<span class="stat-value">{voiceStats.active_rooms}</span>
					</div>
					{#if voiceStats.rooms.length > 0}
						{#each voiceStats.rooms as room}
							{@const ch = getChannels().find(c => c.id === room.channel_id)}
							<div class="stat-row">
								<span class="stat-label">🔊 {ch?.name ?? room.room_name}</span>
								<span class="stat-value">{room.participants} participants</span>
							</div>
						{/each}
					{/if}
				</div>
			</div>
		{/if}

		<!-- E2EE Key Rotation -->
		{#if isEnabled && voiceChannels.length > 0 && isE2eeCapability()}
			<div class="form-section" style="margin-top: 1rem">
				<h3 class="subsection">E2EE Key Rotation</h3>
				<p class="muted" style="margin-bottom: 0.5rem">Force-rotate the encryption key for a voice channel.</p>
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

	.subsection {
		font-size: 0.85rem;
		margin: 0 0 0.35rem;
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
