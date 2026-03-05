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
	type E2EEOptions
} from 'livekit-client';

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
	cbs: LiveKitCallbacks
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
			await room.localParticipant.setMicrophoneEnabled(true);
		} catch {
			console.warn('[Voice] Failed to enable microphone');
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

export async function setMicrophoneEnabled(enabled: boolean): Promise<void> {
	if (!room) return;
	await room.localParticipant.setMicrophoneEnabled(enabled);
}

export async function setCameraEnabled(enabled: boolean): Promise<void> {
	if (!room) return;
	await room.localParticipant.setCameraEnabled(enabled);
}

export async function setScreenShareEnabled(enabled: boolean): Promise<void> {
	if (!room) return;
	await room.localParticipant.setScreenShareEnabled(enabled);
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
