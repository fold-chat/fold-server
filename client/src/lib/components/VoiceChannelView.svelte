<script lang="ts">
	import {
		getVoiceStatesForChannel,
		getCurrentVoiceChannelId,
		getJoiningChannelId,
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
		applyScreenSharePreset,
		leaveCurrentVoice,
		getVoiceLatencyMs,
		getScreenSharePreset,
		optimisticSetServerMute,
		optimisticSetServerDeaf
	} from '$lib/stores/voice.svelte.js';
import { getRoom, getVideoTracks, getLocalVideoTracks, SCREEN_SHARE_PRESETS, type ScreenSharePreset } from '$lib/voice/livekit.js';
	import { getUser, hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { serverMute, serverUnmute, serverDeafen, serverUndeafen, disconnectUser } from '$lib/api/voice.js';
	import { goto } from '$app/navigation';
	import { Track } from 'livekit-client';

	let { channelId, channelName }: { channelId: string; channelName: string } = $props();

	const isConnected = $derived(getCurrentVoiceChannelId() === channelId);
	const isJoining = $derived(getJoiningChannelId() === channelId && !isConnected);
	const canVideo = $derived(hasChannelPermission(channelId, PermissionName.VIDEO));
	const canMuteMembers = $derived(hasChannelPermission(channelId, PermissionName.MUTE_MEMBERS));
	const canDeafenMembers = $derived(hasChannelPermission(channelId, PermissionName.DEAFEN_MEMBERS));
	const voiceUsers = $derived(getVoiceStatesForChannel(channelId));

	// --- Participant list for connected grid ---

	interface ParticipantInfo {
		userId: string;
		displayName: string;
		avatarUrl: string | null;
		isMuted: boolean;
		isDeafened: boolean;
		serverMute: boolean;
		serverDeaf: boolean;
	}

	const participants = $derived.by((): ParticipantInfo[] => {
		const vs = getVoiceStatesForChannel(channelId);
		const user = getUser();
		const list: ParticipantInfo[] = vs.map((s) => ({
			userId: s.user_id,
			displayName: s.display_name || s.username,
			avatarUrl: s.avatar_url,
			isMuted: s.self_mute || s.server_mute,
			isDeafened: s.self_deaf || s.server_deaf,
			serverMute: s.server_mute,
			serverDeaf: s.server_deaf
		}));
		// Local user fallback — webhook voice_state may lag behind LiveKit connection
		if (isConnected && user && !vs.some((s) => s.user_id === user.id)) {
			list.push({
				userId: user.id,
				displayName: user.display_name || user.username,
				avatarUrl: user.avatar_url,
				isMuted: isLocalAudioMuted() || isServerMuted(),
				isDeafened: isLocalDeafened() || isServerDeafened(),
				serverMute: isServerMuted(),
				serverDeaf: isServerDeafened()
			});
		}
		return list;
	});

	// --- Moderation actions ---

	const canModerate = $derived(canMuteMembers || canDeafenMembers);

	async function doServerMute(p: ParticipantInfo) {
		const newState = !p.serverMute;
		optimisticSetServerMute(channelId, p.userId, newState);
		try {
			if (p.serverMute) await serverUnmute(channelId, p.userId);
			else await serverMute(channelId, p.userId);
		} catch {
			optimisticSetServerMute(channelId, p.userId, p.serverMute);
		}
	}

	async function doServerDeafen(p: ParticipantInfo) {
		const newState = !p.serverDeaf;
		optimisticSetServerDeaf(channelId, p.userId, newState);
		try {
			if (p.serverDeaf) await serverUndeafen(channelId, p.userId);
			else await serverDeafen(channelId, p.userId);
		} catch {
			optimisticSetServerDeaf(channelId, p.userId, p.serverDeaf);
		}
	}

	async function doDisconnect(p: ParticipantInfo) {
		try { await disconnectUser(channelId, p.userId); } catch { /* ignore */ }
	}

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
		if (t.source === Track.Source.Camera && !t.publication.isMuted) {
			const track = t.track;
			map.set(t.participant.identity, {
				attach: (el) => track.attach(el),
				detach: (el) => track.detach(el)
			});
		}
	}

	if (room) {
		for (const t of getLocalVideoTracks()) {
			if (t.source === Track.Source.Camera && t.publication.track && !t.publication.isMuted) {
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

	// --- Screen share user set ---

	const screenShareUserIds = $derived.by((): Set<string> => {
		const ids = new Set<string>();
		const user = getUser();
		for (const tile of screenTiles) {
			if (tile.id === 'local-screen') {
				if (user) ids.add(user.id);
			} else {
				ids.add(tile.id.replace('-screen', ''));
			}
		}
		return ids;
	});

	// --- Focus mode ---

	let focusedTileId = $state<string | null>(null);
	let showPresetMenu = $state(false);

	$effect(() => {
		if (!showPresetMenu) return;
		function onDown(e: MouseEvent) {
			if (!(e.target as HTMLElement).closest('.screen-share-group')) showPresetMenu = false;
		}
		document.addEventListener('mousedown', onDown);
		return () => document.removeEventListener('mousedown', onDown);
	});

	const focusedScreen = $derived(
		focusedTileId ? screenTiles.find((t) => t.id === focusedTileId) ?? null : null
	);

	const focusedParticipant = $derived(
		focusedTileId && !focusedScreen
			? participants.find((p) => p.userId === focusedTileId) ?? null
			: null
	);

	// Auto-focus new screen shares
	let prevScreenCount = 0;
	$effect(() => {
		const count = screenTiles.length;
		if (count > prevScreenCount && !focusedTileId && count > 0) {
			focusedTileId = screenTiles[count - 1].id;
		}
		prevScreenCount = count;
	});

	// Clear focus when tile disappears
	$effect(() => {
		if (!focusedTileId) return;
		const exists =
			screenTiles.some((t) => t.id === focusedTileId) ||
			participants.some((p) => p.userId === focusedTileId);
		if (!exists) focusedTileId = null;
	});

	function toggleFocus(tileId: string) {
		focusedTileId = focusedTileId === tileId ? null : tileId;
	}

	// --- Fullscreen ---

	let isFullscreen = $state(false);

	$effect(() => {
		function onFsChange() {
			isFullscreen = !!document.fullscreenElement;
		}
		document.addEventListener('fullscreenchange', onFsChange);
		return () => document.removeEventListener('fullscreenchange', onFsChange);
	});

	function enterFullscreen(event: MouseEvent) {
		const container = (event.currentTarget as HTMLElement).closest('[data-fs-target]') as HTMLElement;
		if (container?.requestFullscreen) container.requestFullscreen();
	}

	// --- Keyboard shortcuts ---

	function cycleFocus(direction: number) {
		const allIds = [...screenTiles.map((t) => t.id), ...participants.map((p) => p.userId)];
		if (allIds.length === 0) return;
		if (!focusedTileId) {
			focusedTileId = allIds[0];
			return;
		}
		const idx = allIds.indexOf(focusedTileId);
		const next = (idx + direction + allIds.length) % allIds.length;
		focusedTileId = allIds[next];
	}

	$effect(() => {
		if (!isConnected) return;
		function onKeydown(e: KeyboardEvent) {
			if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) return;
			if (e.key === 'Escape') {
				if (document.fullscreenElement) document.exitFullscreen();
				else if (focusedTileId) focusedTileId = null;
			} else if ((e.key === 'f' || e.key === 'F') && !e.ctrlKey && !e.metaKey && !e.altKey) {
				if (document.fullscreenElement) {
					document.exitFullscreen();
				} else if (focusedScreen) {
					const el = document.querySelector('.focused-tile.screen[data-fs-target]') as HTMLElement;
					el?.requestFullscreen();
				}
			} else if (e.key === 'ArrowRight' || e.key === 'ArrowDown') {
				e.preventDefault();
				cycleFocus(1);
			} else if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') {
				e.preventDefault();
				cycleFocus(-1);
			}
		}
		document.addEventListener('keydown', onKeydown);
		return () => document.removeEventListener('keydown', onKeydown);
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

	// --- Screen resolution tracking ---

	let screenResolutions = $state<Map<string, { width: number; height: number }>>(new Map());

	function trackScreenRes(el: HTMLVideoElement, tileId: string) {
		let id = tileId;
		function onDims() {
			if (el.videoWidth && el.videoHeight) {
				const cur = screenResolutions.get(id);
				if (!cur || cur.width !== el.videoWidth || cur.height !== el.videoHeight) {
					screenResolutions = new Map(screenResolutions).set(id, { width: el.videoWidth, height: el.videoHeight });
				}
			}
		}
		el.addEventListener('loadedmetadata', onDims);
		el.addEventListener('resize', onDims);
		onDims();
		const poll = setInterval(onDims, 2000);
		return {
			update(newId: string) {
				if (newId !== id) {
					const next = new Map(screenResolutions);
					next.delete(id);
					screenResolutions = next;
					id = newId;
				}
				onDims();
			},
			destroy() {
				el.removeEventListener('loadedmetadata', onDims);
				el.removeEventListener('resize', onDims);
				clearInterval(poll);
				const next = new Map(screenResolutions);
				next.delete(id);
				screenResolutions = next;
			}
		};
	}

	function formatRes(w: number, h: number): string {
		if (h >= 2160) return '4K';
		if (h >= 1440) return '1440p';
		if (h >= 1080) return '1080p';
		if (h >= 720) return '720p';
		if (h >= 480) return '480p';
		return `${h}p`;
	}

	// --- Lobby join ---

	async function handleJoin() {
		if (isJoining) return;
		try {
			await joinVoice(channelId);
		} catch {
			// error shown in sidebar voice error banner
		}
	}
</script>

<div class="voice-channel-view">
	<div class="voice-header">
		<span class="voice-icon">🔊</span>
		<h2 class="voice-channel-name">{channelName}</h2>
		{#if isConnected && getVoiceLatencyMs() > 0}
			<span class="voice-latency" style="color: {getVoiceLatencyMs() <= 80 ? '#2ecc71' : getVoiceLatencyMs() <= 150 ? '#f39c12' : '#e74c3c'}">{getVoiceLatencyMs()}ms</span>
		{/if}
	</div>

	{#if isJoining}
		<!-- ── Joining overlay ── -->
		<div class="voice-joining">
			<div class="joining-spinner"></div>
			<span class="joining-label">Joining voice…</span>
		</div>
	{:else if isConnected}
		<!-- ── Connected view ── -->
		<div class="voice-connected">
			{#snippet fsParticipantStrip()}
				<div class="fs-participant-strip">
					<span class="fs-count">{participants.length} in call</span>
					{#each participants as p (p.userId)}
						{@const fsTrack = cameraTrackMap.get(p.userId)}
						<div class="fs-avatar-tile" class:has-video={!!fsTrack} class:speaking={isSpeaking(p.userId)} title={p.displayName}>
							{#if fsTrack}
								<!-- svelte-ignore a11y_media_has_caption -->
								<video autoplay playsinline muted class="fs-avatar-video" class:mirror={p.userId === getUser()?.id} use:attachVideo={fsTrack}></video>
							{:else if p.avatarUrl}
								<img class="fs-avatar" src={p.avatarUrl} alt="" />
							{:else}
								<div class="fs-avatar-placeholder">{p.displayName.charAt(0).toUpperCase()}</div>
							{/if}
							{#if p.isMuted}<span class="fs-mute" class:server={p.serverMute}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" /></svg></span>{/if}
						</div>
					{/each}
					<button class="fs-exit-btn" onclick={() => document.exitFullscreen()} title="Exit fullscreen">
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
							<polyline points="4 14 10 14 10 20" /><polyline points="20 10 14 10 14 4" />
							<line x1="14" y1="10" x2="21" y2="3" /><line x1="3" y1="21" x2="10" y2="14" />
						</svg>
					</button>
				</div>
			{/snippet}

			{#if focusedTileId}
				<!-- ── Spotlight layout ── -->
				<div class="spotlight-layout">
					<div class="focused-tile-wrap">
						{#if focusedScreen}
						{@const screenDims = screenResolutions.get(focusedScreen.id)}
						<div class="focused-tile screen" data-fs-target>
							<!-- svelte-ignore a11y_media_has_caption -->
							<video autoplay playsinline muted={focusedScreen.isLocal} use:attachVideo={focusedScreen} use:trackScreenRes={focusedScreen.id}></video>
							{#if isFullscreen}
								{#if screenDims}<span class="fs-res-badge" title="{screenDims.width}×{screenDims.height}">{formatRes(screenDims.width, screenDims.height)}</span>{/if}
								{@render fsParticipantStrip()}
							{:else}
								<div class="focused-overlay">
									<span class="screen-name">{focusedScreen.displayName}</span>
									<span class="screen-badge">{focusedScreen.isLocal ? 'Your Screen' : 'Screen'}</span>
									{#if screenDims}<span class="screen-badge res-badge" title="{screenDims.width}×{screenDims.height}">{formatRes(screenDims.width, screenDims.height)}</span>{/if}
									<button class="fs-btn" onclick={(e) => { e.stopPropagation(); enterFullscreen(e); }} title="Fullscreen (F)">
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
											<polyline points="15 3 21 3 21 9" /><polyline points="9 21 3 21 3 15" />
											<line x1="21" y1="3" x2="14" y2="10" /><line x1="3" y1="21" x2="10" y2="14" />
										</svg>
									</button>
									<button class="unpin-btn" onclick={() => (focusedTileId = null)} title="Unpin">
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
											<line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
										</svg>
									</button>
								</div>
							{/if}
						</div>
						{:else if focusedParticipant}
							{@const fp = focusedParticipant}
							{@const cTrack = cameraTrackMap.get(fp.userId)}
							{@const speaking = isSpeaking(fp.userId)}
							{@const audioLevel = getAudioLevel(fp.userId)}
							<div class="focused-tile participant" class:speaking style="--audio-level: {audioLevel}">
								<div class="focused-media">
									{#if cTrack}
										<!-- svelte-ignore a11y_media_has_caption -->
										<video autoplay playsinline muted={fp.userId === getUser()?.id} class="focused-video" class:mirror={fp.userId === getUser()?.id} use:attachVideo={cTrack}></video>
									{:else}
										<div class="focused-avatar-wrap">
											{#if fp.avatarUrl}
												<img class="focused-avatar" src={fp.avatarUrl} alt="" />
											{:else}
												<div class="focused-avatar-placeholder">{fp.displayName.charAt(0).toUpperCase()}</div>
											{/if}
										</div>
									{/if}
								</div>
							<div class="focused-overlay">
								<span class="focused-name">{fp.displayName}</span>
								{#if screenShareUserIds.has(fp.userId)}
									<span class="tile-indicator screen-share-badge" title="Sharing screen">
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
											<rect x="2" y="3" width="20" height="14" rx="2" ry="2" /><line x1="8" y1="21" x2="16" y2="21" /><line x1="12" y1="17" x2="12" y2="21" />
										</svg>
									</span>
								{/if}
								{#if fp.isMuted}<span class="tile-indicator" class:server={fp.serverMute} title={fp.serverMute ? 'Server muted' : 'Muted'}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" /></svg></span>{/if}
								{#if fp.isDeafened}<span class="tile-indicator" class:server={fp.serverDeaf} title={fp.serverDeaf ? 'Server deafened' : 'Deafened'}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><line x1="1" y1="1" x2="23" y2="23" /><path d="M3 12v6a9 9 0 009 3M21 12v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" /></svg></span>{/if}
								<button class="unpin-btn" onclick={() => (focusedTileId = null)} title="Unpin">
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
											<line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
										</svg>
									</button>
								</div>
							</div>
						{/if}
					</div>

					<div class="thumbnail-strip">
						{#each screenTiles as tile (tile.id)}
							<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
							<div
								class="thumb-tile screen-thumb"
								class:thumb-active={tile.id === focusedTileId}
								onclick={() => toggleFocus(tile.id)}
							>
							<div class="thumb-media">
								<!-- svelte-ignore a11y_media_has_caption -->
								<video autoplay playsinline muted class="thumb-video" use:attachVideo={tile}></video>
							</div>
							<span class="thumb-name">{tile.displayName}</span>
							</div>
						{/each}
						{#each participants as p (p.userId)}
							{@const thumbTrack = cameraTrackMap.get(p.userId)}
							<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
							<div
								class="thumb-tile"
								class:speaking={isSpeaking(p.userId)}
								class:thumb-active={p.userId === focusedTileId}
								onclick={() => toggleFocus(p.userId)}
							>
								<div class="thumb-media">
									{#if thumbTrack}
										<!-- svelte-ignore a11y_media_has_caption -->
										<video autoplay playsinline muted class="thumb-video" class:mirror={p.userId === getUser()?.id} use:attachVideo={thumbTrack}></video>
									{:else if p.avatarUrl}
										<img class="thumb-avatar" src={p.avatarUrl} alt="" />
									{:else}
										<div class="thumb-placeholder">{p.displayName.charAt(0).toUpperCase()}</div>
									{/if}
							{#if p.isMuted}
								<span class="thumb-mute" class:server={p.serverMute}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="8" height="8"><line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" /></svg></span>
							{/if}
							{#if screenShareUserIds.has(p.userId)}
								<span class="thumb-screen-share" title="Sharing screen">
									<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10">
										<rect x="2" y="3" width="20" height="14" rx="2" ry="2" /><line x1="8" y1="21" x2="16" y2="21" /><line x1="12" y1="17" x2="12" y2="21" />
									</svg>
								</span>
							{/if}
						</div>
						<span class="thumb-name">{p.displayName}</span>
							</div>
						{/each}
					</div>
				</div>
			{:else}
				<!-- ── Normal grid layout ── -->
			{#if screenTiles.length > 0}
				<div class="screen-tiles">
					{#each screenTiles as tile (tile.id)}
						{@const tileDims = screenResolutions.get(tile.id)}
						<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
						<div class="screen-tile" data-fs-target onclick={() => toggleFocus(tile.id)}>
							<!-- svelte-ignore a11y_media_has_caption -->
							<video autoplay playsinline muted={tile.isLocal} use:attachVideo={tile} use:trackScreenRes={tile.id}></video>
							{#if isFullscreen}
								{#if tileDims}<span class="fs-res-badge" title="{tileDims.width}×{tileDims.height}">{formatRes(tileDims.width, tileDims.height)}</span>{/if}
								{@render fsParticipantStrip()}
							{:else}
								<div class="screen-overlay">
									<span class="screen-name">{tile.displayName}</span>
									<span class="screen-badge">{tile.isLocal ? 'Your Screen' : 'Screen'}</span>
									{#if tileDims}<span class="screen-badge res-badge" title="{tileDims.width}×{tileDims.height}">{formatRes(tileDims.width, tileDims.height)}</span>{/if}
									<button class="fs-btn" onclick={(e) => { e.stopPropagation(); enterFullscreen(e); }} title="Fullscreen">
										<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
											<polyline points="15 3 21 3 21 9" /><polyline points="9 21 3 21 3 15" />
											<line x1="21" y1="3" x2="14" y2="10" /><line x1="3" y1="21" x2="10" y2="14" />
										</svg>
									</button>
								</div>
							{/if}
						</div>
					{/each}
				</div>
			{/if}

				<div class="participant-grid {gridClass}">
					{#each participants as p (p.userId)}
						{@const speaking = isSpeaking(p.userId)}
						{@const audioLevel = getAudioLevel(p.userId)}
						{@const cameraTrack = cameraTrackMap.get(p.userId)}
						<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
						<div
							class="participant-tile"
							class:speaking
							class:muted={p.isMuted}
							class:deafened={p.isDeafened}
							style="--audio-level: {audioLevel}"
							onclick={() => toggleFocus(p.userId)}
						>
							<div class="tile-media">
								{#if cameraTrack}
									<!-- svelte-ignore a11y_media_has_caption -->
									<video
										autoplay
										playsinline
										muted={p.userId === getUser()?.id}
										class="tile-video"
										class:mirror={p.userId === getUser()?.id}
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
							{#if canModerate && p.userId !== getUser()?.id}
								<div class="tile-mod-overlay">
									{#if canMuteMembers}
										<button class="mod-btn" class:active={p.serverMute} title={p.serverMute ? 'Server Unmute' : 'Server Mute'} onclick={(e) => { e.stopPropagation(); doServerMute(p); }}>
											<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
												{#if p.serverMute}
													<line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" />
												{:else}
													<path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z" /><path d="M19 10v2a7 7 0 01-14 0v-2" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" />
												{/if}
											</svg>
										</button>
									{/if}
									{#if canDeafenMembers}
										<button class="mod-btn" class:active={p.serverDeaf} title={p.serverDeaf ? 'Server Undeafen' : 'Server Deafen'} onclick={(e) => { e.stopPropagation(); doServerDeafen(p); }}>
											<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
												{#if p.serverDeaf}
													<line x1="1" y1="1" x2="23" y2="23" /><path d="M3 12v6a9 9 0 009 3M21 12v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" />
												{:else}
													<path d="M3 18v-6a9 9 0 0118 0v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" />
												{/if}
											</svg>
										</button>
									{/if}
									{#if canMuteMembers}
										<button class="mod-btn danger" title="Disconnect" onclick={(e) => { e.stopPropagation(); doDisconnect(p); }}>
											<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="14" height="14">
												<path d="M10.68 13.31a16 16 0 003.41 2.6l1.27-1.27a2 2 0 012.11-.45 12.84 12.84 0 002.81.7 2 2 0 011.72 2v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6 19.79 19.79 0 01-3.07-8.67A2 2 0 014.11 2h3a2 2 0 012 1.72 12.84 12.84 0 00.7 2.81 2 2 0 01-.45 2.11L8.09 9.91" />
												<line x1="23" y1="1" x2="1" y2="23" />
											</svg>
										</button>
									{/if}
								</div>
							{/if}
						<div class="tile-footer">
							<span class="tile-name">{p.displayName}</span>
							{#if screenShareUserIds.has(p.userId)}
								<span class="tile-indicator screen-share-badge" title="Sharing screen">
									<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12">
										<rect x="2" y="3" width="20" height="14" rx="2" ry="2" /><line x1="8" y1="21" x2="16" y2="21" /><line x1="12" y1="17" x2="12" y2="21" />
									</svg>
								</span>
							{/if}
							{#if p.isMuted}
								<span class="tile-indicator" class:server={p.serverMute} title={p.serverMute ? 'Server muted' : 'Muted'}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" /></svg></span>
							{/if}
							{#if p.isDeafened}
								<span class="tile-indicator" class:server={p.serverDeaf} title={p.serverDeaf ? 'Server deafened' : 'Deafened'}><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12"><line x1="1" y1="1" x2="23" y2="23" /><path d="M3 12v6a9 9 0 009 3M21 12v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" /></svg></span>
							{/if}
						</div>
					</div>
					{/each}
				</div>
			{/if}

			<!-- Controls bar -->
			<div class="voice-controls">
				<button
					class="ctrl-btn"
					class:active={isLocalAudioMuted() || isServerMuted()}
					class:server-enforced={isServerMuted() && !canMuteMembers}
					title={isServerMuted() ? (canMuteMembers ? 'Remove server mute' : 'Server muted') : isLocalAudioMuted() ? 'Unmute' : 'Mute'}
					onclick={() => toggleMute(canMuteMembers)}
					disabled={isServerMuted() && !canMuteMembers}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
						{#if isLocalAudioMuted() || isServerMuted()}
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
					class:server-enforced={isServerDeafened() && !canDeafenMembers}
					title={isServerDeafened() ? (canDeafenMembers ? 'Remove server deafen' : 'Server deafened') : isLocalDeafened() ? 'Undeafen' : 'Deafen'}
					onclick={() => toggleDeafen(canDeafenMembers)}
					disabled={isServerDeafened() && !canDeafenMembers}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
						{#if isLocalDeafened() || isServerDeafened()}
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

					<div class="screen-share-group">
						<button
							class="ctrl-btn ss-main"
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
						<button
							class="ctrl-btn ss-chevron"
							class:active={isScreenShareActive()}
							title="Screen share quality"
							onclick={() => (showPresetMenu = !showPresetMenu)}
						>
							<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="12" height="12">
								<polyline points="6 9 12 15 18 9" />
							</svg>
						</button>
						{#if showPresetMenu}
						<div class="preset-menu">
								{#each Object.entries(SCREEN_SHARE_PRESETS) as [key, preset]}
									<button
										class="preset-item"
										class:selected={getScreenSharePreset() === key}
										onclick={() => { applyScreenSharePreset(key as ScreenSharePreset); showPresetMenu = false; }}
									>
										<span class="preset-label">{preset.label}</span>
										<span class="preset-desc">{preset.desc}</span>
										{#if preset.warn}
											<span class="preset-warn">⚠ {preset.warn}</span>
										{/if}
									</button>
								{/each}
							</div>
						{/if}
					</div>
				{/if}

				<button
					class="ctrl-btn"
					title="Device Settings"
					onclick={() => goto('/settings/devices')}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
						<circle cx="12" cy="12" r="3" />
						<path d="M19.4 15a1.65 1.65 0 00.33 1.82l.06.06a2 2 0 010 2.83 2 2 0 01-2.83 0l-.06-.06a1.65 1.65 0 00-1.82-.33 1.65 1.65 0 00-1 1.51V21a2 2 0 01-4 0v-.09A1.65 1.65 0 009 19.4a1.65 1.65 0 00-1.82.33l-.06.06a2 2 0 01-2.83-2.83l.06-.06A1.65 1.65 0 004.68 15a1.65 1.65 0 00-1.51-1H3a2 2 0 010-4h.09A1.65 1.65 0 004.6 9a1.65 1.65 0 00-.33-1.82l-.06-.06a2 2 0 012.83-2.83l.06.06A1.65 1.65 0 009 4.68a1.65 1.65 0 001-1.51V3a2 2 0 014 0v.09a1.65 1.65 0 001 1.51 1.65 1.65 0 001.82-.33l.06-.06a2 2 0 012.83 2.83l-.06.06A1.65 1.65 0 0019.4 9a1.65 1.65 0 001.51 1H21a2 2 0 010 4h-.09a1.65 1.65 0 00-1.51 1z" />
					</svg>
				</button>

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
									<span class="user-indicator server" title="Server muted"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" /></svg></span>
								{:else if vu.self_mute}
									<span class="user-indicator" title="Muted"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" /></svg></span>
								{/if}
								{#if vu.server_deaf}
									<span class="user-indicator server" title="Server deafened"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M3 12v6a9 9 0 009 3M21 12v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" /></svg></span>
								{:else if vu.self_deaf}
									<span class="user-indicator" title="Deafened"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M3 12v6a9 9 0 009 3M21 12v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" /></svg></span>
								{/if}
							</div>
						{/each}
					</div>
				</div>
			{:else}
				<p class="empty-label">No one is in voice yet.</p>
			{/if}

		<button class="join-btn" onclick={handleJoin} disabled={isJoining}>
				{isJoining ? 'Joining…' : 'Join Voice'}
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

	.voice-latency {
		font-size: 0.7rem;
		font-weight: 500;
		margin-left: auto;
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
		cursor: pointer;
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

	video.mirror {
		transform: scaleX(-1);
	}

	.res-badge {
		background: rgba(46, 204, 113, 0.3);
		font-variant-numeric: tabular-nums;
	}

	.fs-res-badge {
		position: absolute;
		top: 8px;
		right: 8px;
		font-size: 0.6rem;
		background: rgba(0, 0, 0, 0.6);
		color: white;
		padding: 2px 6px;
		border-radius: 3px;
		z-index: 2;
		font-variant-numeric: tabular-nums;
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
		position: relative;
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
		cursor: pointer;
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
		display: inline-flex;
		align-items: center;
		color: var(--text-muted);
		flex-shrink: 0;
	}

	.tile-indicator.server {
		color: var(--danger, #e74c3c);
	}

	/* Tile moderation overlay */

	.tile-mod-overlay {
		position: absolute;
		top: 4px;
		right: 4px;
		display: flex;
		gap: 3px;
		opacity: 0;
		transition: opacity 0.12s;
		z-index: 2;
	}

	.participant-tile:hover .tile-mod-overlay {
		opacity: 1;
	}

	.mod-btn {
		background: rgba(0, 0, 0, 0.6);
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		padding: 4px;
		border-radius: 4px;
		display: flex;
		align-items: center;
		justify-content: center;
		transition: background 0.1s, color 0.1s;
	}

	.mod-btn:hover {
		background: rgba(0, 0, 0, 0.85);
		color: var(--text);
	}

	.mod-btn.active {
		color: var(--accent, #5865f2);
	}

	.mod-btn.danger {
		color: var(--danger, #e74c3c);
	}

	.mod-btn.danger:hover {
		color: var(--danger, #e74c3c);
		background: rgba(231, 76, 60, 0.3);
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

	/* Screen share split button */

	.screen-share-group {
		position: relative;
		display: flex;
	}

	.ss-main {
		border-radius: 6px 0 0 6px;
		border-right: none;
	}

	.ss-chevron {
		border-radius: 0 6px 6px 0;
		padding: 0.45rem 0.25rem;
	}

	.preset-menu {
		position: absolute;
		bottom: calc(100% + 6px);
		right: 0;
		background: var(--bg-surface, #2b2d31);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 4px;
		min-width: 200px;
		z-index: 20;
		box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
	}

	.preset-item {
		width: 100%;
		display: flex;
		flex-direction: column;
		gap: 1px;
		padding: 6px 10px;
		border: none;
		background: none;
		color: var(--text);
		cursor: pointer;
		border-radius: 4px;
		text-align: left;
	}

	.preset-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.06));
	}

	.preset-item.selected {
		background: rgba(88, 101, 242, 0.15);
	}

	.preset-label {
		font-size: 0.8rem;
		font-weight: 500;
	}

	.preset-desc {
		font-size: 0.65rem;
		color: var(--text-muted);
	}

	.preset-warn {
		font-size: 0.6rem;
		color: var(--warning, #f0a020);
		line-height: 1.3;
	}

	/* ── Joining overlay ── */

	.voice-joining {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 1rem;
	}

	.joining-spinner {
		width: 32px;
		height: 32px;
		border: 3px solid var(--border);
		border-top-color: var(--accent, #5865f2);
		border-radius: 50%;
		animation: spin 0.8s linear infinite;
	}

	@keyframes spin {
		to { transform: rotate(360deg); }
	}

	.joining-label {
		font-size: 0.875rem;
		color: var(--text-muted);
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
		display: inline-flex;
		align-items: center;
		color: var(--text-muted);
	}

	.user-indicator.server {
		color: var(--danger, #e74c3c);
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
	/* ── Spotlight layout ── */

	.spotlight-layout {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-height: 0;
		overflow: hidden;
	}

	.focused-tile-wrap {
		flex: 1;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 8px;
		min-height: 0;
	}

	.focused-tile {
		position: relative;
		border-radius: 10px;
		overflow: hidden;
		max-height: 100%;
		max-width: 100%;
	}

	.focused-tile.screen {
		background: #000;
		width: 100%;
		height: 100%;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.focused-tile.screen video {
		width: 100%;
		height: 100%;
		object-fit: contain;
		display: block;
	}

	.focused-tile.participant {
		background: var(--bg-surface, #2b2d31);
		border: 2px solid transparent;
		transition: border-color 0.1s, box-shadow 0.15s;
		width: 100%;
		max-width: 50rem;
	}

	.focused-tile.participant.speaking {
		border-color: #2ecc71;
		box-shadow:
			0 0 0 1px #2ecc71,
			0 0 calc(6px + 18px * var(--audio-level, 0))
				rgba(46, 204, 113, calc(0.35 + 0.4 * var(--audio-level, 0)));
	}

	.focused-media {
		width: 100%;
		aspect-ratio: 16 / 9;
		display: flex;
		align-items: center;
		justify-content: center;
		overflow: hidden;
	}

	.focused-video {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}

	.focused-avatar-wrap {
		width: 35%;
		aspect-ratio: 1;
	}

	.focused-avatar {
		width: 100%;
		height: 100%;
		border-radius: 50%;
		object-fit: cover;
		display: block;
	}

	.focused-avatar-placeholder {
		width: 100%;
		height: 100%;
		border-radius: 50%;
		background: var(--bg-active, rgba(255, 255, 255, 0.1));
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 3rem;
		font-weight: 700;
		color: var(--text-muted);
	}

	.focused-overlay {
		position: absolute;
		bottom: 0;
		left: 0;
		right: 0;
		padding: 0.5rem 0.75rem;
		background: linear-gradient(transparent, rgba(0, 0, 0, 0.7));
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.focused-name {
		font-size: 0.85rem;
		color: white;
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.unpin-btn {
		background: rgba(255, 255, 255, 0.15);
		border: none;
		color: white;
		cursor: pointer;
		padding: 4px;
		border-radius: 4px;
		display: flex;
		align-items: center;
		justify-content: center;
		margin-left: auto;
		transition: background 0.1s;
	}

	.unpin-btn:hover {
		background: rgba(255, 255, 255, 0.3);
	}

	/* Thumbnail strip */

	.thumbnail-strip {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		padding: 10px 12px;
		overflow-x: auto;
		flex-shrink: 0;
		border-top: 1px solid var(--border);
		background: var(--bg-surface, #2b2d31);
	}

	.thumb-tile {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 4px;
		cursor: pointer;
		flex-shrink: 0;
	}

	.thumb-media {
		position: relative;
		width: 64px;
		height: 64px;
		border-radius: 10px;
		border: 2px solid transparent;
		overflow: hidden;
		background: var(--bg-active, rgba(255, 255, 255, 0.1));
		display: flex;
		align-items: center;
		justify-content: center;
		transition: border-color 0.15s;
	}

	.thumb-tile:hover .thumb-media {
		border-color: var(--text-muted);
	}

	.thumb-tile.thumb-active .thumb-media {
		border-color: var(--accent, #5865f2);
	}

	.thumb-tile.speaking .thumb-media {
		border-color: #2ecc71;
	}

	.thumb-tile.screen-thumb .thumb-media {
		color: var(--text-muted);
	}

	.thumb-tile.screen-thumb.thumb-active .thumb-media {
		color: var(--accent, #5865f2);
	}

	.thumb-video {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}

	.thumb-avatar {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}

	.thumb-placeholder {
		width: 100%;
		height: 100%;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.1rem;
		font-weight: 700;
		color: var(--text-muted);
	}

	.thumb-mute {
		position: absolute;
		bottom: -2px;
		right: -2px;
		background: var(--bg-surface, #2b2d31);
		border-radius: 50%;
		padding: 1px;
		line-height: 1;
		display: flex;
		align-items: center;
		justify-content: center;
		color: var(--text-muted);
	}

	.thumb-mute.server {
		color: var(--danger, #e74c3c);
	}

	.thumb-name {
		font-size: 0.65rem;
		color: var(--text-muted);
		max-width: 72px;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		text-align: center;
		opacity: 0;
		transition: opacity 0.15s;
	}

	.thumb-tile:hover .thumb-name {
		opacity: 1;
	}

	/* Screen share badge */

	.screen-share-badge {
		display: inline-flex;
		align-items: center;
		color: var(--accent, #5865f2);
	}

	.thumb-screen-share {
		position: absolute;
		top: 2px;
		left: 2px;
		color: var(--accent, #5865f2);
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--bg-surface, #2b2d31);
		border-radius: 3px;
		padding: 1px;
		line-height: 1;
	}

	/* Fullscreen button */

	.fs-btn {
		background: rgba(255, 255, 255, 0.15);
		border: none;
		color: white;
		cursor: pointer;
		padding: 4px;
		border-radius: 4px;
		display: flex;
		align-items: center;
		justify-content: center;
		transition: background 0.1s;
	}

	.fs-btn:hover {
		background: rgba(255, 255, 255, 0.3);
	}

	/* Fullscreen participant strip */

	.fs-participant-strip {
		position: absolute;
		bottom: 0;
		left: 0;
		right: 0;
		padding: 12px 16px;
		background: linear-gradient(transparent, rgba(0, 0, 0, 0.85));
		display: flex;
		align-items: center;
		gap: 12px;
		z-index: 10;
	}

	.screen-tile:fullscreen,
	.focused-tile.screen:fullscreen {
		width: 100vw;
		height: 100vh;
		max-width: none;
		max-height: none;
		border-radius: 0;
		background: #000;
	}

	.screen-tile:fullscreen video {
		max-height: none;
	}

	.fs-count {
		font-size: 0.7rem;
		color: rgba(255, 255, 255, 0.7);
		white-space: nowrap;
		flex-shrink: 0;
	}

	.fs-avatar-tile {
		position: relative;
		width: 40px;
		height: 40px;
		border-radius: 50%;
		overflow: hidden;
		flex-shrink: 0;
		border: 2px solid transparent;
		transition: border-color 0.15s;
	}

	.fs-avatar-tile.has-video {
		width: 56px;
		height: 42px;
		border-radius: 8px;
	}

	.fs-avatar-tile.speaking {
		border-color: #2ecc71;
		box-shadow: 0 0 8px rgba(46, 204, 113, 0.5);
	}

	.fs-avatar,
	.fs-avatar-video {
		width: 100%;
		height: 100%;
		object-fit: cover;
		display: block;
	}

	.fs-avatar-placeholder {
		width: 100%;
		height: 100%;
		background: rgba(255, 255, 255, 0.15);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.8rem;
		font-weight: 700;
		color: white;
	}

	.fs-mute {
		position: absolute;
		bottom: -2px;
		right: -2px;
		background: rgba(0, 0, 0, 0.7);
		border-radius: 50%;
		padding: 1px;
		line-height: 1;
		display: flex;
		align-items: center;
		justify-content: center;
		color: var(--text-muted);
	}

	.fs-mute.server {
		color: var(--danger, #e74c3c);
	}

	.fs-exit-btn {
		background: rgba(255, 255, 255, 0.15);
		border: none;
		color: white;
		cursor: pointer;
		padding: 8px;
		border-radius: 6px;
		display: flex;
		align-items: center;
		justify-content: center;
		margin-left: auto;
		flex-shrink: 0;
		transition: background 0.1s;
	}

	.fs-exit-btn:hover {
		background: rgba(255, 255, 255, 0.3);
	}

</style>
