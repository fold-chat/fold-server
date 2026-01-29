<script lang="ts">
	import { getRoom, getVideoTracks, getLocalVideoTracks } from '$lib/voice/livekit.js';
	import { Track } from 'livekit-client';
	import { getCurrentVoiceChannelId, getHasVideoTracks, isCameraActive, isScreenShareActive, toggleCamera, toggleScreenShare, isSpeaking } from '$lib/stores/voice.svelte.js';
	import { getChannelById } from '$lib/stores/channels.svelte.js';
	import { hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { getUser } from '$lib/stores/auth.svelte.js';

	const channelId = $derived(getCurrentVoiceChannelId());
	const channel = $derived(channelId ? getChannelById(channelId) : null);
	const hasVideo = $derived(getHasVideoTracks());
	const canVideo = $derived(channelId ? hasChannelPermission(channelId, PermissionName.VIDEO) : false);
	const localCameraOn = $derived(isCameraActive());
	const localScreenOn = $derived(isScreenShareActive());
	const me = $derived(getUser());

	interface VideoTile {
		id: string;
		name: string;
		identity: string;
		isLocal: boolean;
		isScreenShare: boolean;
		attachTrack: (el: HTMLVideoElement) => void;
		detachTrack: (el: HTMLVideoElement) => void;
	}

	// Svelte action to attach/detach video track to element
	function attachVideo(el: HTMLVideoElement, tile: VideoTile) {
		tile.attachTrack(el);
		return {
			update(newTile: VideoTile) {
				tile.detachTrack(el);
				newTile.attachTrack(el);
				tile = newTile;
			},
			destroy() {
				tile.detachTrack(el);
			}
		};
	}

	const tiles = $derived.by((): VideoTile[] => {
		const result: VideoTile[] = [];
		const room = getRoom();
		if (!room) return result;

		// Remote video tracks
		for (const t of getVideoTracks()) {
			result.push({
				id: `${t.participant.identity}-${t.source}`,
				name: t.participant.name || t.participant.identity,
				identity: t.participant.identity,
				isLocal: false,
				isScreenShare: t.source === Track.Source.ScreenShare,
				attachTrack: (el) => t.track.attach(el),
				detachTrack: (el) => t.track.detach(el)
			});
		}

		// Local video tracks
		for (const t of getLocalVideoTracks()) {
			if (t.publication.track) {
				const track = t.publication.track;
				result.push({
					id: `local-${t.source}`,
					name: me?.display_name || me?.username || 'You',
					identity: room.localParticipant.identity,
					isLocal: true,
					isScreenShare: t.source === Track.Source.ScreenShare,
					attachTrack: (el) => track.attach(el),
					detachTrack: (el) => track.detach(el)
				});
			}
		}

		return result;
	});
</script>

{#if channelId && (hasVideo || localCameraOn || localScreenOn)}
	<div class="voice-panel">
		<div class="voice-panel-header">
			<span class="voice-panel-title">🔊 {channel?.name ?? 'Voice'}</span>
			<span class="e2ee-badge" title="End-to-end encrypted">🔒 E2EE</span>
		</div>

		<div class="video-grid" class:single={tiles.length === 1} class:duo={tiles.length === 2}>
			{#each tiles as tile (tile.id)}
				<div class="video-tile" class:speaking={isSpeaking(tile.identity)} class:screen-share={tile.isScreenShare}>
					<!-- svelte-ignore a11y_media_has_caption -->
					<video
						autoplay
						playsinline
						muted={tile.isLocal}
						use:attachVideo={tile}
					></video>
					<div class="tile-overlay">
						<span class="tile-name">{tile.name}</span>
						{#if tile.isScreenShare}
							<span class="tile-badge">Screen</span>
						{/if}
						{#if tile.isLocal}
							<span class="tile-badge">You</span>
						{/if}
						<span class="tile-e2ee" title="Encrypted">🔒</span>
					</div>
				</div>
			{/each}
		</div>

		{#if canVideo}
			<div class="voice-panel-controls">
				<button
					class="panel-btn"
					class:active={localCameraOn}
					onclick={toggleCamera}
					title={localCameraOn ? 'Turn off camera' : 'Turn on camera'}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
						{#if localCameraOn}
							<path d="M23 7l-7 5 7 5V7z" />
							<rect x="1" y="5" width="15" height="14" rx="2" ry="2" />
						{:else}
							<line x1="1" y1="1" x2="23" y2="23" />
							<path d="M21 21H3a2 2 0 01-2-2V8a2 2 0 012-2h3m3-3h6l2 3h4a2 2 0 012 2v9.34" />
						{/if}
					</svg>
				</button>
				<button
					class="panel-btn"
					class:active={localScreenOn}
					onclick={toggleScreenShare}
					title={localScreenOn ? 'Stop sharing' : 'Share screen'}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
						<rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
						<line x1="8" y1="21" x2="16" y2="21" />
						<line x1="12" y1="17" x2="12" y2="21" />
					</svg>
				</button>
			</div>
		{/if}
	</div>
{/if}

<style>
	.voice-panel {
		border-bottom: 1px solid var(--border);
		background: var(--bg, #1e1f22);
		display: flex;
		flex-direction: column;
	}

	.voice-panel-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.4rem 0.75rem;
		border-bottom: 1px solid var(--border);
	}

	.voice-panel-title {
		font-size: 0.8rem;
		font-weight: 600;
		color: var(--text);
	}

	.e2ee-badge {
		font-size: 0.65rem;
		color: #2ecc71;
		display: flex;
		align-items: center;
		gap: 0.2rem;
	}

	.video-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
		gap: 4px;
		padding: 4px;
		max-height: 50vh;
		overflow-y: auto;
	}

	.video-grid.single {
		grid-template-columns: 1fr;
	}

	.video-grid.duo {
		grid-template-columns: 1fr 1fr;
	}

	.video-tile {
		position: relative;
		background: #000;
		border-radius: 6px;
		overflow: hidden;
		aspect-ratio: 16/9;
	}

	.video-tile.speaking {
		outline: 2px solid #2ecc71;
		outline-offset: -2px;
	}

	.video-tile.screen-share {
		grid-column: 1 / -1;
		aspect-ratio: unset;
		max-height: 40vh;
	}

	.video-tile video {
		width: 100%;
		height: 100%;
		object-fit: contain;
	}

	.tile-overlay {
		position: absolute;
		bottom: 0;
		left: 0;
		right: 0;
		padding: 0.3rem 0.5rem;
		background: linear-gradient(transparent, rgba(0, 0, 0, 0.7));
		display: flex;
		align-items: center;
		gap: 0.3rem;
	}

	.tile-name {
		font-size: 0.7rem;
		color: white;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		flex: 1;
	}

	.tile-badge {
		font-size: 0.6rem;
		background: rgba(255, 255, 255, 0.2);
		color: white;
		padding: 0.1rem 0.3rem;
		border-radius: 3px;
	}

	.tile-e2ee {
		font-size: 0.55rem;
		flex-shrink: 0;
	}

	.voice-panel-controls {
		display: flex;
		justify-content: center;
		gap: 0.5rem;
		padding: 0.4rem;
		border-top: 1px solid var(--border);
	}

	.panel-btn {
		background: none;
		border: 1px solid var(--border);
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.35rem 0.6rem;
		border-radius: 4px;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.panel-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.08));
		color: var(--text);
	}

	.panel-btn.active {
		background: rgba(88, 101, 242, 0.15);
		border-color: var(--accent, #5865f2);
		color: var(--accent, #5865f2);
	}
</style>
