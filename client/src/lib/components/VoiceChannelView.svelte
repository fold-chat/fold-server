<script lang="ts">
	import {
		getVoiceStatesForChannel,
		getCurrentVoiceChannelId,
		joinVoice,
		isSpeaking,
		getAudioLevel,
		getVideoTrackRevision,
		isLocalAudioMuted,
		isLocalDeafened,
		isServerMuted,
		isServerDeafened,
		isCameraActive,
		isScreenShareActive,
		toggleMute,
		toggleDeafen,
		toggleCamera,
		toggleScreenShare,
		leaveCurrentVoice
	} from '$lib/stores/voice.svelte.js';
	import { getRoom, getVideoTracks, getLocalVideoTracks } from '$lib/voice/livekit.js';
	import { getUser, hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { Track } from 'livekit-client';

	let { channelId, channelName }: { channelId: string; channelName: string } = $props();

	const isConnected = $derived(getCurrentVoiceChannelId() === channelId);
	const canVideo = $derived(hasChannelPermission(channelId, PermissionName.VIDEO));
	const voiceUsers = $derived(getVoiceStatesForChannel(channelId));

	// --- Participant list for connected grid ---

	interface ParticipantInfo {
		userId: string;
		displayName: string;
		avatarUrl: string | null;
		isMuted: boolean;
		isDeafened: boolean;
	}

	const participants = $derived.by((): ParticipantInfo[] => {
		const vs = getVoiceStatesForChannel(channelId);
		const user = getUser();
		const list: ParticipantInfo[] = vs.map((s) => ({
			userId: s.user_id,
			displayName: s.display_name || s.username,
			avatarUrl: s.avatar_url,
			isMuted: s.self_mute || s.server_mute,
			isDeafened: s.self_deaf || s.server_deaf
		}));
		// Local user fallback — server voice state may lag briefly after join
		if (user && !vs.some((s) => s.user_id === user.id)) {
			list.push({
				userId: user.id,
				displayName: user.display_name || user.username,
				avatarUrl: user.avatar_url,
				isMuted: isLocalAudioMuted() || isServerMuted(),
				isDeafened: isLocalDeafened() || isServerDeafened()
			});
		}
		return list;
	});

	const gridClass = $derived.by(() => {
		const n = participants.length;
		if (n <= 1) return 'grid-1';
		if (n <= 4) return 'grid-2';
		if (n <= 9) return 'grid-3';
		return 'grid-4';
	});

	// --- Video track helpers ---

	interface VideoTrackRef {
		attach: (el: HTMLVideoElement) => void;
		detach: (el: HTMLVideoElement) => void;
	}

	interface ScreenTile extends VideoTrackRef {
		id: string;
		displayName: string;
		isLocal: boolean;
	}

	// Map userId -> camera track (reactive via videoTrackRevision)
	const cameraTrackMap = $derived.by((): Map<string, VideoTrackRef> => {
		void getVideoTrackRevision();
		const map = new Map<string, VideoTrackRef>();
		const room = getRoom();

		for (const t of getVideoTracks()) {
			if (t.source === Track.Source.Camera) {
				const track = t.track;
				map.set(t.participant.identity, {
					attach: (el) => track.attach(el),
					detach: (el) => track.detach(el)
				});
			}
		}

		if (room) {
			for (const t of getLocalVideoTracks()) {
				if (t.source === Track.Source.Camera && t.publication.track) {
					const track = t.publication.track;
					map.set(room.localParticipant.identity, {
						attach: (el) => track.attach(el),
						detach: (el) => track.detach(el)
					});
				}
			}
		}

		return map;
	});

	const screenTiles = $derived.by((): ScreenTile[] => {
		void getVideoTrackRevision();
		const tiles: ScreenTile[] = [];
		const room = getRoom();

		for (const t of getVideoTracks()) {
			if (t.source === Track.Source.ScreenShare) {
				const track = t.track;
				tiles.push({
					id: `${t.participant.identity}-screen`,
					displayName: t.participant.name || t.participant.identity,
					isLocal: false,
					attach: (el) => track.attach(el),
					detach: (el) => track.detach(el)
				});
			}
		}

		if (room) {
			for (const t of getLocalVideoTracks()) {
				if (t.source === Track.Source.ScreenShare && t.publication.track) {
					const track = t.publication.track;
					const user = getUser();
					tiles.push({
						id: 'local-screen',
						displayName: user?.display_name || user?.username || 'You',
						isLocal: true,
						attach: (el) => track.attach(el),
						detach: (el) => track.detach(el)
					});
				}
			}
		}

		return tiles;
	});

	// Svelte action: attach/detach a video track to a <video> element
	function attachVideo(el: HTMLVideoElement, tile: VideoTrackRef) {
		tile.attach(el);
		return {
			update(newTile: VideoTrackRef) {
				tile.detach(el);
				newTile.attach(el);
				tile = newTile;
			},
			destroy() {
				tile.detach(el);
			}
		};
	}

	// --- Lobby join ---

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

	{#if isConnected}
		<!-- ── Connected view ── -->
		<div class="voice-connected">
			{#if screenTiles.length > 0}
				<div class="screen-tiles">
					{#each screenTiles as tile (tile.id)}
						<div class="screen-tile">
							<!-- svelte-ignore a11y_media_has_caption -->
							<video autoplay playsinline muted={tile.isLocal} use:attachVideo={tile}></video>
							<div class="screen-overlay">
								<span class="screen-name">{tile.displayName}</span>
								<span class="screen-badge">{tile.isLocal ? 'Your Screen' : 'Screen'}</span>
							</div>
						</div>
					{/each}
				</div>
			{/if}

			<div class="participant-grid {gridClass}">
				{#each participants as p (p.userId)}
					{@const speaking = isSpeaking(p.userId)}
					{@const audioLevel = getAudioLevel(p.userId)}
					{@const cameraTrack = cameraTrackMap.get(p.userId)}
					<div
						class="participant-tile"
						class:speaking
						class:muted={p.isMuted}
						class:deafened={p.isDeafened}
						style="--audio-level: {audioLevel}"
					>
						<div class="tile-media">
							{#if cameraTrack}
								<!-- svelte-ignore a11y_media_has_caption -->
								<video
									autoplay
									playsinline
									muted={p.userId === getUser()?.id}
									class="tile-video"
									use:attachVideo={cameraTrack}
								></video>
							{:else}
								<div class="avatar-wrap">
									{#if p.avatarUrl}
										<img class="tile-avatar" src={p.avatarUrl} alt="" />
									{:else}
										<div class="tile-avatar-placeholder">
											{p.displayName.charAt(0).toUpperCase()}
										</div>
									{/if}
								</div>
							{/if}
						</div>
						<div class="tile-footer">
							<span class="tile-name">{p.displayName}</span>
							{#if p.isMuted}
								<span class="tile-indicator" title="Muted">🔇</span>
							{/if}
							{#if p.isDeafened}
								<span class="tile-indicator" title="Deafened">🔕</span>
							{/if}
						</div>
					</div>
				{/each}
			</div>

			<!-- Controls bar -->
			<div class="voice-controls">
				{#if isServerMuted()}
					<span class="server-indicator" title="Server muted">🔇 Muted</span>
				{/if}
				{#if isServerDeafened()}
					<span class="server-indicator" title="Server deafened">🔕 Deafened</span>
				{/if}

				<button
					class="ctrl-btn"
					class:active={isLocalAudioMuted() || isServerMuted()}
					class:server-enforced={isServerMuted()}
					title={isServerMuted() ? 'Server muted' : isLocalAudioMuted() ? 'Unmute' : 'Mute'}
					onclick={toggleMute}
					disabled={isServerMuted()}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
						{#if isLocalAudioMuted()}
							<line x1="1" y1="1" x2="23" y2="23" />
							<path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" />
							<path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" />
							<line x1="12" y1="19" x2="12" y2="23" />
							<line x1="8" y1="23" x2="16" y2="23" />
						{:else}
							<path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z" />
							<path d="M19 10v2a7 7 0 01-14 0v-2" />
							<line x1="12" y1="19" x2="12" y2="23" />
							<line x1="8" y1="23" x2="16" y2="23" />
						{/if}
					</svg>
				</button>

				<button
					class="ctrl-btn"
					class:active={isLocalDeafened() || isServerDeafened()}
					class:server-enforced={isServerDeafened()}
					title={isServerDeafened() ? 'Server deafened' : isLocalDeafened() ? 'Undeafen' : 'Deafen'}
					onclick={toggleDeafen}
					disabled={isServerDeafened()}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
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

				{#if canVideo}
					<button
						class="ctrl-btn"
						class:active={isCameraActive()}
						title={isCameraActive() ? 'Turn off camera' : 'Turn on camera'}
						onclick={toggleCamera}
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
							{#if isCameraActive()}
								<path d="M23 7l-7 5 7 5V7z" />
								<rect x="1" y="5" width="15" height="14" rx="2" ry="2" />
							{:else}
								<line x1="1" y1="1" x2="23" y2="23" />
								<path d="M21 21H3a2 2 0 01-2-2V8a2 2 0 012-2h3m3-3h6l2 3h4a2 2 0 012 2v9.34" />
							{/if}
						</svg>
					</button>

					<button
						class="ctrl-btn"
						class:active={isScreenShareActive()}
						title={isScreenShareActive() ? 'Stop sharing' : 'Share screen'}
						onclick={toggleScreenShare}
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
							<rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
							<line x1="8" y1="21" x2="16" y2="21" />
							<line x1="12" y1="17" x2="12" y2="21" />
						</svg>
					</button>
				{/if}

				<button
					class="ctrl-btn disconnect-btn"
					title="Disconnect"
					onclick={() => leaveCurrentVoice()}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
						<path d="M10.68 13.31a16 16 0 003.41 2.6l1.27-1.27a2 2 0 012.11-.45 12.84 12.84 0 002.81.7 2 2 0 011.72 2v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6 19.79 19.79 0 01-3.07-8.67A2 2 0 014.11 2h3a2 2 0 012 1.72 12.84 12.84 0 00.7 2.81 2 2 0 01-.45 2.11L8.09 9.91" />
						<line x1="23" y1="1" x2="1" y2="23" />
					</svg>
				</button>
			</div>
		</div>
	{:else}
		<!-- ── Lobby view ── -->
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

			<button class="join-btn" onclick={handleJoin} disabled={joining}>
				{joining ? 'Joining…' : 'Join Voice'}
			</button>
		</div>
	{/if}
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
		flex-shrink: 0;
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

	/* ── Connected view ── */

	.voice-connected {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-height: 0;
		overflow: hidden;
	}

	/* Screen share tiles */

	.screen-tiles {
		display: flex;
		flex-direction: column;
		gap: 4px;
		padding: 8px 8px 0;
		max-height: 45vh;
		overflow-y: auto;
		flex-shrink: 0;
	}

	.screen-tile {
		position: relative;
		background: #000;
		border-radius: 8px;
		overflow: hidden;
	}

	.screen-tile video {
		width: 100%;
		height: 100%;
		object-fit: contain;
		display: block;
		max-height: 40vh;
	}

	.screen-overlay {
		position: absolute;
		bottom: 0;
		left: 0;
		right: 0;
		padding: 0.4rem 0.6rem;
		background: linear-gradient(transparent, rgba(0, 0, 0, 0.75));
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.screen-name {
		font-size: 0.75rem;
		color: white;
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.screen-badge {
		font-size: 0.6rem;
		background: rgba(255, 255, 255, 0.2);
		color: white;
		padding: 0.1rem 0.35rem;
		border-radius: 3px;
	}

	/* Participant grid */

	.participant-grid {
		flex: 1;
		display: grid;
		gap: 8px;
		padding: 8px;
		overflow-y: auto;
		align-content: center;
		align-items: center;
		justify-content: center;
	}

	.participant-grid.grid-1 {
		grid-template-columns: minmax(0, 20rem);
	}

	.participant-grid.grid-2 {
		grid-template-columns: repeat(2, minmax(0, 20rem));
	}

	.participant-grid.grid-3 {
		grid-template-columns: repeat(3, minmax(0, 20rem));
	}

	.participant-grid.grid-4 {
		grid-template-columns: repeat(4, minmax(0, 20rem));
	}

	/* Participant tile */

	.participant-tile {
		max-width:20rem;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.5rem;
		border-radius: 10px;
		background: var(--bg-surface, #2b2d31);
		border: 2px solid transparent;
		overflow: hidden;
		transition: border-color 0.1s, box-shadow 0.15s;
		padding-bottom: 0.5rem;
	}

	.participant-tile.speaking {
		border-color: #2ecc71;
		box-shadow:
			0 0 0 1px #2ecc71,
			0 0 calc(6px + 18px * var(--audio-level, 0))
				rgba(46, 204, 113, calc(0.35 + 0.4 * var(--audio-level, 0)));
	}

	.participant-tile.muted {
		opacity: 0.75;
	}

	.participant-tile.deafened {
		opacity: 0.5;
	}

	/* Avatar (no video) */

	.tile-media {
		width: 100%;
		aspect-ratio: 1;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.avatar-wrap {
		width: 60%;
		aspect-ratio: 1;
	}

	.tile-avatar {
		width: 100%;
		height: 100%;
		border-radius: 50%;
		object-fit: cover;
		display: block;
	}

	.tile-avatar-placeholder {
		width: 100%;
		height: 100%;
		border-radius: 50%;
		background: var(--bg-active, rgba(255, 255, 255, 0.1));
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.5rem;
		font-weight: 700;
		color: var(--text-muted);
	}

	/* Camera video tile */

	.tile-video {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}

	/* Tile footer */

	.tile-footer {
		display: flex;
		align-items: center;
		gap: 0.3rem;
		padding: 0 0.5rem;
		width: 100%;
		justify-content: center;
	}

	.tile-name {
		font-size: 0.75rem;
		color: var(--text);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 80%;
	}

	.tile-indicator {
		font-size: 0.65rem;
		flex-shrink: 0;
	}

	/* Voice controls bar */

	.voice-controls {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0.5rem;
		padding: 0.5rem 1rem;
		border-top: 1px solid var(--border);
		flex-shrink: 0;
		background: var(--bg-surface, #2b2d31);
	}

	.server-indicator {
		font-size: 0.7rem;
		color: var(--danger, #e74c3c);
		white-space: nowrap;
	}

	.ctrl-btn {
		background: var(--bg-hover, rgba(255, 255, 255, 0.06));
		border: 1px solid var(--border);
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.45rem 0.6rem;
		border-radius: 6px;
		display: flex;
		align-items: center;
		justify-content: center;
		transition: background 0.1s, color 0.1s, border-color 0.1s;
	}

	.ctrl-btn:hover:not(:disabled) {
		background: var(--bg-active, rgba(255, 255, 255, 0.1));
		color: var(--text);
	}

	.ctrl-btn.active {
		background: rgba(88, 101, 242, 0.15);
		border-color: var(--accent, #5865f2);
		color: var(--accent, #5865f2);
	}

	.ctrl-btn.server-enforced {
		background: rgba(231, 76, 60, 0.12);
		border-color: var(--danger, #e74c3c);
		color: var(--danger, #e74c3c);
		cursor: not-allowed;
	}

	.ctrl-btn:disabled {
		opacity: 0.6;
	}

	.disconnect-btn {
		color: var(--danger, #e74c3c);
		border-color: var(--danger, #e74c3c);
	}

	.disconnect-btn:hover:not(:disabled) {
		background: rgba(231, 76, 60, 0.15);
		color: var(--danger, #e74c3c);
	}

	/* ── Lobby view ── */

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
</style>
