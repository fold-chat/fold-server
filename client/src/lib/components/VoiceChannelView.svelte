<script lang="ts">
	import { getVoiceStatesForChannel, getCurrentVoiceChannelId, joinVoice, isSpeaking } from '$lib/stores/voice.svelte.js';

	let { channelId, channelName }: { channelId: string; channelName: string } = $props();

	const voiceUsers = $derived(getVoiceStatesForChannel(channelId));
	const isConnected = $derived(getCurrentVoiceChannelId() === channelId);

	let joining = $state(false);

	async function handleJoin() {
		if (joining) return;
		joining = true;
		try {
			await joinVoice(channelId);
		} catch {
			// error shown in sidebar voice error banner
		} finally {
			joining = false;
		}
	}
</script>

<div class="voice-channel-view">
	<div class="voice-header">
		<span class="voice-icon">🔊</span>
		<h2 class="voice-channel-name">{channelName}</h2>
	</div>

	<div class="voice-lobby">
		{#if voiceUsers.length > 0}
			<div class="voice-users-section">
				<span class="section-label">In Voice — {voiceUsers.length}</span>
				<div class="voice-user-list">
					{#each voiceUsers as vu}
						<div
							class="voice-user-tile"
							class:speaking={isSpeaking(vu.user_id)}
							class:muted={vu.self_mute || vu.server_mute}
							class:deafened={vu.self_deaf || vu.server_deaf}
						>
							{#if vu.avatar_url}
								<img class="user-avatar" src={vu.avatar_url} alt="" />
							{:else}
								<div class="user-avatar-placeholder">
									{(vu.display_name || vu.username).charAt(0).toUpperCase()}
								</div>
							{/if}
							<span class="user-name">{vu.display_name || vu.username}</span>
							{#if vu.server_mute}
								<span class="user-indicator" title="Server muted">🔇</span>
							{:else if vu.self_mute}
								<span class="user-indicator" title="Muted">🔇</span>
							{/if}
							{#if vu.server_deaf}
								<span class="user-indicator" title="Server deafened">🔕</span>
							{:else if vu.self_deaf}
								<span class="user-indicator" title="Deafened">🔕</span>
							{/if}
						</div>
					{/each}
				</div>
			</div>
		{:else}
			<p class="empty-label">No one is in voice yet.</p>
		{/if}

		{#if isConnected}
			<div class="connected-notice">✓ Connected to this channel</div>
		{:else}
			<button class="join-btn" onclick={handleJoin} disabled={joining}>
				{joining ? 'Joining…' : 'Join Voice'}
			</button>
		{/if}
	</div>
</div>

<style>
	.voice-channel-view {
		flex: 1;
		display: flex;
		flex-direction: column;
		height: 100vh;
		min-width: 0;
		background: var(--bg, #1e1f22);
	}

	.voice-header {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.5rem 1rem;
		border-bottom: 1px solid var(--border);
		min-height: 44px;
	}

	.voice-icon {
		font-size: 1rem;
		opacity: 0.7;
	}

	.voice-channel-name {
		font-size: 0.9rem;
		font-weight: 600;
		margin: 0;
	}

	.voice-lobby {
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		flex: 1;
		gap: 1.5rem;
		padding: 2rem;
	}

	.voice-users-section {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.75rem;
		width: 100%;
		max-width: 400px;
	}

	.section-label {
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
	}

	.voice-user-list {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		justify-content: center;
		width: 100%;
	}

	.voice-user-tile {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.3rem;
		padding: 0.6rem 0.75rem;
		border-radius: 8px;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		min-width: 72px;
		transition: border-color 0.1s;
	}

	.voice-user-tile.speaking {
		border-color: #2ecc71;
	}

	.voice-user-tile.muted {
		opacity: 0.7;
	}

	.voice-user-tile.deafened {
		opacity: 0.5;
	}

	.user-avatar {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		object-fit: cover;
	}

	.voice-user-tile.speaking .user-avatar,
	.voice-user-tile.speaking .user-avatar-placeholder {
		outline: 2px solid #2ecc71;
		outline-offset: 2px;
	}

	.user-avatar-placeholder {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		background: var(--bg-active, rgba(255, 255, 255, 0.1));
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1rem;
		font-weight: 600;
		color: var(--text-muted);
	}

	.user-name {
		font-size: 0.75rem;
		color: var(--text);
		text-align: center;
		max-width: 80px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.user-indicator {
		font-size: 0.6rem;
	}

	.empty-label {
		font-size: 0.875rem;
		color: var(--text-muted);
		margin: 0;
	}

	.join-btn {
		padding: 0.6rem 2rem;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 6px;
		font-size: 0.875rem;
		font-weight: 600;
		cursor: pointer;
		transition: opacity 0.15s;
	}

	.join-btn:hover:not(:disabled) {
		opacity: 0.85;
	}

	.join-btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.connected-notice {
		font-size: 0.8rem;
		color: #2ecc71;
		font-weight: 600;
	}
</style>
