import {
	Room,
	RoomEvent,
	Track,
	type RemoteTrack,
	type RemoteTrackPublication,
	type RemoteParticipant,
	type Participant,
	type LocalTrack,
	type LocalTrackPublication,
	ConnectionState,
	ExternalE2EEKeyProvider,
	type E2EEOptions,
	type ScreenShareCaptureOptions,
	type TrackPublishOptions,
	ScreenSharePresets
} from 'livekit-client';

// --- Screen share presets ---

export type ScreenSharePreset = 'auto' | 'detail' | 'presentation' | 'gaming' | 'gaming-hfr' | '4k' | 'low';

export const SCREEN_SHARE_PRESETS: Record<ScreenSharePreset, { label: string; desc: string; warn?: string }> = {
	auto: { label: 'Auto', desc: 'Browser defaults' },
	detail: { label: 'Detail', desc: '1080p 5fps — text & code' },
	presentation: { label: 'Presentation', desc: '1080p 15fps — slides' },
	gaming: { label: 'Gaming', desc: '1080p 30fps — smooth motion' },
	'gaming-hfr': { label: 'Gaming 60fps', desc: '1080p 60fps — ultra smooth', warn: 'High CPU & bandwidth. 60fps capture not supported on all systems.' },
	'4k': { label: '4K', desc: '2160p 30fps — maximum clarity', warn: 'Very high bandwidth (~10 Mbps upload). Viewers also need sufficient bandwidth.' },
	low: { label: 'Low', desc: '720p 10fps — save bandwidth' }
};

function getScreenShareOpts(preset: ScreenSharePreset): {
	capture?: ScreenShareCaptureOptions;
	publish?: TrackPublishOptions;
} {
	switch (preset) {
		case 'detail':
			return {
				capture: { resolution: { width: 1920, height: 1080, frameRate: 5 }, contentHint: 'detail' },
				publish: {
					screenShareEncoding: { maxBitrate: 1_500_000, maxFramerate: 5 },
					screenShareSimulcastLayers: [ScreenSharePresets.h720fps5, ScreenSharePresets.h360fps3]
				}
			};
		case 'presentation':
			return {
				capture: { resolution: { width: 1920, height: 1080, frameRate: 15 } },
				publish: {
					screenShareEncoding: { maxBitrate: 2_500_000, maxFramerate: 15 },
					screenShareSimulcastLayers: [ScreenSharePresets.h720fps15, ScreenSharePresets.h360fps3]
				}
			};
		case 'gaming':
			return {
				capture: { resolution: { width: 1920, height: 1080, frameRate: 30 }, contentHint: 'motion' },
				publish: {
					screenShareEncoding: { maxBitrate: 4_000_000, maxFramerate: 30 },
					screenShareSimulcastLayers: [ScreenSharePresets.h720fps15, ScreenSharePresets.h360fps3]
				}
			};
		case 'gaming-hfr':
			return {
				capture: { resolution: { width: 1920, height: 1080, frameRate: 60 }, contentHint: 'motion' },
				publish: {
					screenShareEncoding: { maxBitrate: 6_000_000, maxFramerate: 60 },
					screenShareSimulcastLayers: [ScreenSharePresets.h720fps30, ScreenSharePresets.h360fps15]
				}
			};
		case '4k':
			return {
				capture: { resolution: { width: 3840, height: 2160, frameRate: 30 } },
				publish: {
					screenShareEncoding: { maxBitrate: 10_000_000, maxFramerate: 30 },
					screenShareSimulcastLayers: [ScreenSharePresets.h1080fps30, ScreenSharePresets.h720fps15]
				}
			};
		case 'low':
			return {
				capture: { resolution: { width: 1280, height: 720, frameRate: 10 } },
				publish: {
					screenShareEncoding: { maxBitrate: 1_000_000, maxFramerate: 10 },
					screenShareSimulcastLayers: [ScreenSharePresets.h360fps3]
				}
			};
		default:
			return {
				publish: {
					screenShareSimulcastLayers: [ScreenSharePresets.h720fps5, ScreenSharePresets.h360fps3]
				}
			};
	}
}

// --- Types ---

export interface LiveKitCallbacks {
	onSpeakersChanged: (speakers: Array<{ identity: string; audioLevel: number }>) => void;
	onTrackSubscribed: (track: RemoteTrack, pub: RemoteTrackPublication, participant: RemoteParticipant) => void;
	onTrackUnsubscribed: (track: RemoteTrack, pub: RemoteTrackPublication, participant: RemoteParticipant) => void;
	onConnectionStateChanged: (state: ConnectionState) => void;
	onE2EEStateChanged?: (participantIdentity: string, state: string) => void;
	onParticipantConnected?: (identity: string) => void;
	onParticipantDisconnected?: (identity: string) => void;
	onLocalTrackPublished?: () => void;
	onLocalTrackUnpublished?: () => void;
	onMicPermissionDenied?: () => void;
}

// --- State ---

let room: Room | null = null;
let keyProvider: ExternalE2EEKeyProvider | null = null;
let callbacks: LiveKitCallbacks | null = null;
let intentionalDisconnect = false;

// --- Public API ---

export function getRoom(): Room | null {
	return room;
}

export function isConnected(): boolean {
	return room !== null && room.state === ConnectionState.Connected;
}

/**
 * Connect to a LiveKit room, optionally with E2EE.
 * @param url LiveKit WS URL
 * @param token LiveKit access token
 * @param encryptionKey Base64-encoded AES-256 key (omit to skip E2EE)
 * @param keyIndex Key index for ratcheting
 * @param cbs Event callbacks
 */
export async function connectToRoom(
	url: string,
	token: string,
	encryptionKey: string | undefined,
	keyIndex: number | undefined,
	cbs: LiveKitCallbacks,
	audioInputDeviceId?: string,
	audioOutputDeviceId?: string
): Promise<Room> {
	// Disconnect existing room if any
	if (room) {
		await disconnectFromRoom(false);
	}

	callbacks = cbs;

	// Set up E2EE only when an encryption key is provided
	let e2eeOptions: E2EEOptions | undefined;
	if (encryptionKey) {
		keyProvider = new ExternalE2EEKeyProvider();
		const keyBytes = base64ToArrayBuffer(encryptionKey);
		await keyProvider.setKey(keyBytes);

		try {
			const workerUrl = new URL('livekit-client/e2ee-worker', import.meta.url);
			e2eeOptions = {
				keyProvider,
				worker: new Worker(workerUrl, { type: 'module' })
			};
		} catch {
			console.warn('[Voice] E2EE worker failed to load — connecting without encryption');
		}
	}

	// Create and configure room
	room = new Room({
		adaptiveStream: true,
		dynacast: true,
		publishDefaults: {
			simulcast: true,
			screenShareSimulcastLayers: [ScreenSharePresets.h720fps5, ScreenSharePresets.h360fps3]
		},
		...(e2eeOptions ? { e2ee: e2eeOptions } : {})
	});

	// Attach event listeners
	room.on(RoomEvent.ActiveSpeakersChanged, handleSpeakersChanged);
	room.on(RoomEvent.TrackSubscribed, handleTrackSubscribed);
	room.on(RoomEvent.TrackUnsubscribed, handleTrackUnsubscribed);
	room.on(RoomEvent.ConnectionStateChanged, handleConnectionStateChanged);
	room.on(RoomEvent.ParticipantConnected, handleParticipantConnected);
	room.on(RoomEvent.ParticipantDisconnected, handleParticipantDisconnected);
	room.on(RoomEvent.LocalTrackPublished, handleLocalTrackPublished);
	room.on(RoomEvent.LocalTrackUnpublished, handleLocalTrackUnpublished);

	// Pre-check microphone permission before connecting
	let micAllowed = true;
	try {
		const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
		stream.getTracks().forEach((t) => t.stop());
	} catch {
		micAllowed = false;
		cbs.onMicPermissionDenied?.();
	}

	// Connect
	intentionalDisconnect = false;
	await room.connect(url, token);

	// Enable E2EE if available
	if (e2eeOptions) {
		try {
			await room.setE2EEEnabled(true);
			callbacks?.onE2EEStateChanged?.('local', 'enabled');
		} catch {
			console.warn('[Voice] Failed to enable E2EE');
			callbacks?.onE2EEStateChanged?.('local', 'failed');
		}
	} else if (encryptionKey) {
		// E2EE requested but worker failed (Firefox etc)
		callbacks?.onE2EEStateChanged?.('local', 'unsupported');
	} else {
		// E2EE not enabled on server
		callbacks?.onE2EEStateChanged?.('local', 'disabled');
	}

	// Enable microphone if permission was granted
	if (micAllowed) {
		try {
			await room.localParticipant.setMicrophoneEnabled(true, audioInputDeviceId ? { deviceId: audioInputDeviceId } : undefined);
		} catch {
			console.warn('[Voice] Failed to enable microphone');
		}
	}

	// Set audio output device if specified
	if (audioOutputDeviceId) {
		try {
			await room.switchActiveDevice('audiooutput', audioOutputDeviceId);
		} catch {
			console.warn('[Voice] Failed to set audio output device');
		}
	}

	return room;
}

/**
 * Disconnect from the current LiveKit room.
 * @param notifyServer Whether to call DELETE /voice (true by default)
 */
export async function disconnectFromRoom(notifyServer = true): Promise<void> {
	intentionalDisconnect = true;
	if (room) {
		room.removeAllListeners();
		await room.disconnect();
		room = null;
	}
	keyProvider = null;
	callbacks = null;
}

/**
 * Update E2EE key (on key rotation).
 */
export async function updateEncryptionKey(encryptionKey: string): Promise<void> {
	if (!keyProvider) return;
	const keyBytes = base64ToArrayBuffer(encryptionKey);
	await keyProvider.setKey(keyBytes);
}

// --- Local track controls ---

export async function setMicrophoneEnabled(enabled: boolean, deviceId?: string): Promise<void> {
	if (!room) return;
	await room.localParticipant.setMicrophoneEnabled(enabled, deviceId ? { deviceId } : undefined);
}

export async function setCameraEnabled(enabled: boolean, deviceId?: string): Promise<void> {
	if (!room) return;
	await room.localParticipant.setCameraEnabled(enabled, deviceId ? { deviceId } : undefined);
}

// --- Mid-call device switching ---

export async function switchAudioInput(deviceId: string): Promise<void> {
	if (!room) return;
	await room.switchActiveDevice('audioinput', deviceId);
}

export async function switchVideoInput(deviceId: string): Promise<void> {
	if (!room) return;
	await room.switchActiveDevice('videoinput', deviceId);
}

export async function switchAudioOutput(deviceId: string): Promise<void> {
	if (!room) return;
	await room.switchActiveDevice('audiooutput', deviceId);
}

/**
 * Update an active screen share for a new preset.
 * Swaps the capture track via getDisplayMedia + replaceTrack when resolution
 * differs (avoids stop/restart which loses user-gesture context).
 * Bitrate, framerate, contentHint always updated in-place.
 */
export async function updateScreenShareEncoding(preset: ScreenSharePreset): Promise<void> {
	if (!room) return;
	const pub = room.localParticipant.getTrackPublication(Track.Source.ScreenShare);
	if (!pub?.track) return;

	const opts = getScreenShareOpts(preset);
	const track = pub.track;

	// If target resolution differs, get a new capture and swap the track
	const targetRes = opts.capture?.resolution;
	if (targetRes) {
		const curH = track.mediaStreamTrack.getSettings().height ?? 0;
		if (curH && Math.abs(curH - targetRes.height) > 100) {
			const stream = await navigator.mediaDevices.getDisplayMedia({
				video: {
					width: { ideal: targetRes.width },
					height: { ideal: targetRes.height },
					frameRate: { ideal: targetRes.frameRate ?? 30 }
				}
			});
			const newMediaTrack = stream.getVideoTracks()[0];
			if (newMediaTrack) {
				newMediaTrack.contentHint = opts.capture?.contentHint ?? '';
				await track.replaceTrack(newMediaTrack);
			}
		}
	}

	// Update content hint on current track
	track.mediaStreamTrack.contentHint = opts.capture?.contentHint ?? '';

	// Apply frame rate constraint to capture
	if (opts.capture?.resolution?.frameRate) {
		try {
			await track.mediaStreamTrack.applyConstraints({
				frameRate: { max: opts.capture.resolution.frameRate }
			});
		} catch { /* not supported for all display media */ }
	}

	// Update sender encoding (bitrate + framerate)
	const sender = track.sender;
	if (sender && opts.publish?.screenShareEncoding) {
		try {
			const params = sender.getParameters();
			if (params.encodings?.length > 0) {
				const enc = opts.publish.screenShareEncoding;
				params.encodings[0].maxBitrate = enc.maxBitrate;
				if (enc.maxFramerate !== undefined) {
					params.encodings[0].maxFramerate = enc.maxFramerate;
				}
				await sender.setParameters(params);
			}
		} catch { /* sender update failed */ }
	}
}

export async function setScreenShareEnabled(enabled: boolean, preset: ScreenSharePreset = 'auto'): Promise<void> {
	if (!room) return;
	if (!enabled) {
		await room.localParticipant.setScreenShareEnabled(false);
		return;
	}
	const opts = getScreenShareOpts(preset);
	await room.localParticipant.setScreenShareEnabled(true, opts.capture, opts.publish);
}

export function isCameraEnabled(): boolean {
	if (!room) return false;
	const pub = room.localParticipant.getTrackPublication(Track.Source.Camera);
	return pub !== undefined && !pub.isMuted;
}

export function isScreenShareEnabled(): boolean {
	if (!room) return false;
	const pub = room.localParticipant.getTrackPublication(Track.Source.ScreenShare);
	return pub !== undefined && !pub.isMuted;
}

export function isMicrophoneEnabled(): boolean {
	if (!room) return false;
	const pub = room.localParticipant.getTrackPublication(Track.Source.Microphone);
	return pub !== undefined && !pub.isMuted;
}

/**
 * Get all video/screen share track publications from remote participants.
 */
export function getVideoTracks(): Array<{
	participant: RemoteParticipant;
	publication: RemoteTrackPublication;
	track: RemoteTrack;
	source: Track.Source;
}> {
	if (!room) return [];
	const tracks: Array<{
		participant: RemoteParticipant;
		publication: RemoteTrackPublication;
		track: RemoteTrack;
		source: Track.Source;
	}> = [];

	for (const p of room.remoteParticipants.values()) {
		for (const pub of p.trackPublications.values()) {
			if (
				pub.track &&
				(pub.source === Track.Source.Camera || pub.source === Track.Source.ScreenShare)
			) {
				tracks.push({
					participant: p,
					publication: pub,
					track: pub.track,
					source: pub.source
				});
			}
		}
	}
	return tracks;
}

/**
 * Get local video publications (camera + screen share).
 */
export function getLocalVideoTracks(): Array<{
	publication: LocalTrackPublication;
	source: Track.Source;
}> {
	if (!room) return [];
	const tracks: Array<{ publication: LocalTrackPublication; source: Track.Source }> = [];
	for (const pub of room.localParticipant.trackPublications.values()) {
		if (pub.source === Track.Source.Camera || pub.source === Track.Source.ScreenShare) {
			tracks.push({ publication: pub, source: pub.source });
		}
	}
	return tracks;
}

// --- Event handlers ---

function handleSpeakersChanged(speakers: Participant[]) {
	callbacks?.onSpeakersChanged(speakers.map((s) => ({ identity: s.identity, audioLevel: s.audioLevel })));
}

function handleTrackSubscribed(
	track: RemoteTrack,
	pub: RemoteTrackPublication,
	participant: RemoteParticipant
) {
	callbacks?.onTrackSubscribed(track, pub, participant);
}

function handleTrackUnsubscribed(
	track: RemoteTrack,
	pub: RemoteTrackPublication,
	participant: RemoteParticipant
) {
	callbacks?.onTrackUnsubscribed(track, pub, participant);
}

function handleConnectionStateChanged(state: ConnectionState) {
	// Only fire unexpected-disconnect logic when not user-initiated
	if (state === ConnectionState.Disconnected && intentionalDisconnect) {
		// Intentional — just update state
		callbacks?.onConnectionStateChanged(state);
		return;
	}
	callbacks?.onConnectionStateChanged(state);
}

function handleParticipantConnected(participant: RemoteParticipant) {
	callbacks?.onParticipantConnected?.(participant.identity);
}

function handleParticipantDisconnected(participant: RemoteParticipant) {
	callbacks?.onParticipantDisconnected?.(participant.identity);
}

function handleLocalTrackPublished(_pub: LocalTrackPublication) {
	callbacks?.onLocalTrackPublished?.();
}

function handleLocalTrackUnpublished(_pub: LocalTrackPublication) {
	callbacks?.onLocalTrackUnpublished?.();
}

// --- Per-participant track helpers ---

/**
 * Get the camera track for a participant (local or remote) by their user identity.
 */
export function getParticipantVideoTrack(userId: string): RemoteTrack | LocalTrack | null {
	if (!room) return null;
	if (room.localParticipant.identity === userId) {
		const pub = room.localParticipant.getTrackPublication(Track.Source.Camera);
		return pub?.track ?? null;
	}
	const participant = room.remoteParticipants.get(userId);
	if (!participant) return null;
	const pub = participant.getTrackPublication(Track.Source.Camera);
	return pub?.track ?? null;
}

/**
 * Get the screen share track for a participant (local or remote) by their user identity.
 */
export function getParticipantScreenTrack(userId: string): RemoteTrack | LocalTrack | null {
	if (!room) return null;
	if (room.localParticipant.identity === userId) {
		const pub = room.localParticipant.getTrackPublication(Track.Source.ScreenShare);
		return pub?.track ?? null;
	}
	const participant = room.remoteParticipants.get(userId);
	if (!participant) return null;
	const pub = participant.getTrackPublication(Track.Source.ScreenShare);
	return pub?.track ?? null;
}

// --- Latency ---

export function getSignalRtt(): number {
	return room?.engine?.client?.rtt ?? 0;
}

// --- Helpers ---

function base64ToArrayBuffer(base64: string): ArrayBuffer {
	const binary = atob(base64);
	const bytes = new Uint8Array(binary.length);
	for (let i = 0; i < binary.length; i++) {
		bytes[i] = binary.charCodeAt(i);
	}
	return bytes.buffer;
}
