<script lang="ts">
	import {
		getCurrentVoiceChannelId,
		getJoiningChannelId,
		isLocalAudioMuted,
		isLocalDeafened,
		isServerMuted,
		isServerDeafened,
		toggleMute,
		toggleDeafen,
		leaveCurrentVoice,
		getLivekitConnectionState
	} from '$lib/stores/voice.svelte.js';
	import { getChannelById } from '$lib/stores/channels.svelte.js';
	import { hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { goto } from '$app/navigation';

	const channelId = $derived(getCurrentVoiceChannelId() ?? getJoiningChannelId());
	const channelName = $derived(channelId ? getChannelById(channelId)?.name ?? 'Voice' : null);
	const isJoiningOnly = $derived(!!getJoiningChannelId() && !getCurrentVoiceChannelId());

	const canMuteMembers = $derived.by(() => {
		const id = getCurrentVoiceChannelId();
		if (!id) return false;
		return hasChannelPermission(id, PermissionName.MUTE_MEMBERS);
	});

	const canDeafenMembers = $derived.by(() => {
		const id = getCurrentVoiceChannelId();
		if (!id) return false;
		return hasChannelPermission(id, PermissionName.DEAFEN_MEMBERS);
	});

	function navigateToChannel() {
		if (channelId) goto(`/channels/${channelId}`);
	}
</script>

{#if channelId}
	<div class="mobile-voice-bar">
		<button class="voice-info" onclick={navigateToChannel}>
			<span class="voice-icon">🔊</span>
			<div class="voice-text">
				{#if isJoiningOnly}
					<span class="voice-status joining">Joining…</span>
				{:else if getLivekitConnectionState() === 'reconnecting'}
					<span class="voice-status reconnecting">Reconnecting…</span>
				{:else}
					<span class="voice-status connected">Voice Connected</span>
				{/if}
				<span class="voice-channel">{channelName}</span>
			</div>
		</button>

		<div class="voice-actions">
			<button
				class="vb-btn"
				class:active={isLocalAudioMuted() || isServerMuted()}
				class:server-enforced={isServerMuted() && !canMuteMembers}
				title={isServerMuted() ? (canMuteMembers ? 'Remove server mute' : 'Server muted') : isLocalAudioMuted() ? 'Unmute' : 'Mute'}
				onclick={() => toggleMute(canMuteMembers)}
				disabled={isServerMuted() && !canMuteMembers}
			>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
					{#if isLocalAudioMuted()}
						<line x1="1" y1="1" x2="23" y2="23" />
						<path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" />
						<path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" />
						<line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" />
					{:else}
						<path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z" />
						<path d="M19 10v2a7 7 0 01-14 0v-2" />
						<line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" />
					{/if}
				</svg>
			</button>

			<button
				class="vb-btn"
				class:active={isLocalDeafened() || isServerDeafened()}
				class:server-enforced={isServerDeafened() && !canDeafenMembers}
				title={isServerDeafened() ? (canDeafenMembers ? 'Remove server deafen' : 'Server deafened') : isLocalDeafened() ? 'Undeafen' : 'Deafen'}
				onclick={() => toggleDeafen(canDeafenMembers)}
				disabled={isServerDeafened() && !canDeafenMembers}
			>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
					{#if isLocalDeafened()}
						<line x1="1" y1="1" x2="23" y2="23" />
						<path d="M3 12v6a9 9 0 009 3M21 12v6" />
						<path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" />
					{:else}
						<path d="M3 18v-6a9 9 0 0118 0v6" />
						<path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" />
					{/if}
				</svg>
			</button>

			<button class="vb-btn" title="Device Settings" onclick={() => goto('/settings/devices')}>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
					<circle cx="12" cy="12" r="3" />
					<path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" />
				</svg>
			</button>

			<button class="vb-btn disconnect" title="Disconnect" onclick={() => leaveCurrentVoice()}>
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
					<path d="M10.68 13.31a16 16 0 003.41 2.6l1.27-1.27a2 2 0 012.11-.45 12.84 12.84 0 002.81.7 2 2 0 011.72 2v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6 19.79 19.79 0 01-3.07-8.67A2 2 0 014.11 2h3a2 2 0 012 1.72 12.84 12.84 0 00.7 2.81 2 2 0 01-.45 2.11L8.09 9.91" />
					<line x1="23" y1="1" x2="1" y2="23" />
				</svg>
			</button>
		</div>
	</div>
{/if}

<style>
	.mobile-voice-bar {
		position: fixed;
		bottom: 0;
		left: 0;
		right: 0;
		z-index: 60;
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.4rem 0.75rem;
		background: var(--bg-surface, #2b2d31);
		border-top: 1px solid var(--border);
		gap: 0.5rem;
	}

	.voice-info {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: none;
		border: none;
		color: var(--text);
		cursor: pointer;
		padding: 0;
		min-width: 0;
		flex: 1;
	}

	.voice-icon {
		font-size: 0.85rem;
		flex-shrink: 0;
	}

	.voice-text {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.voice-status {
		font-size: 0.65rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.04em;
	}

	.voice-status.connected { color: #2ecc71; }
	.voice-status.joining { color: #f39c12; }
	.voice-status.reconnecting { color: #f39c12; }

	.voice-channel {
		font-size: 0.75rem;
		color: var(--text-muted);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.voice-actions {
		display: flex;
		gap: 0.35rem;
		flex-shrink: 0;
	}

	.vb-btn {
		background: var(--bg-hover, rgba(255, 255, 255, 0.06));
		border: 1px solid var(--border);
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.35rem;
		border-radius: 6px;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.vb-btn.active {
		background: rgba(88, 101, 242, 0.15);
		border-color: var(--accent, #5865f2);
		color: var(--accent, #5865f2);
	}

	.vb-btn.disconnect {
		color: var(--danger, #e74c3c);
		border-color: var(--danger, #e74c3c);
	}

	.vb-btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.vb-btn.server-enforced {
		background: rgba(231, 76, 60, 0.12);
		border-color: var(--danger, #e74c3c);
		color: var(--danger, #e74c3c);
	}
</style>
