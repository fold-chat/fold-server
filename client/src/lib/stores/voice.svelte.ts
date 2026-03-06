import type { VoiceState } from '$lib/api/voice.js';
import { getVoiceToken, leaveVoice as leaveVoiceApi, leaveVoiceBeacon, updateVoiceState as updateVoiceStateApi } from '$lib/api/voice.js';
import { getUser } from '$lib/stores/auth.svelte.js';
import {
	connectToRoom,
	disconnectFromRoom,
	updateEncryptionKey,
	setMicrophoneEnabled,
	setCameraEnabled as lkSetCamera,
	setScreenShareEnabled as lkSetScreenShare,
	updateScreenShareEncoding as lkUpdateScreenShareEncoding,
	isCameraEnabled as lkIsCameraEnabled,
	isScreenShareEnabled as lkIsScreenShareEnabled,
	getVideoTracks,
	getLocalVideoTracks,
	getParticipantVideoTrack as lkGetParticipantVideoTrack,
	getParticipantScreenTrack as lkGetParticipantScreenTrack,
	getSignalRtt,
	type LiveKitCallbacks,
	type ScreenSharePreset
} from '$lib/voice/livekit.js';
import { Track, ConnectionState } from 'livekit-client';

// --- State ---

let voiceStates = $state<Map<string, VoiceState[]>>(new Map());
let currentVoiceChannelId = $state<string | null>(null);
let joiningChannelId = $state<string | null>(null);
let localAudioMuted = $state(false);
let localDeafened = $state(false);
let voiceVideoEnabled = $state(false);
let voiceMode = $state<string>('off');

// Mic permission denied (user joins muted)
let micPermissionDenied = $state(false);

// E2EE key state
let currentEncryptionKey = $state<string | null>(null);
let currentKeyIndex = $state<number>(0);

// Server-enforced mute/deaf (cannot be overridden by user)
let serverMuted = $state(false);
let serverDeafened = $state(false);

// E2EE availability
let e2eeActive = $state(false);
let e2eeCapability = $state(false);

// LiveKit integration state
let speakingUserIds = $state<Set<string>>(new Set());
let audioLevels = $state<Map<string, number>>(new Map());
let connectedParticipantIds = $state<Set<string>>(new Set());
let cameraActive = $state(false);
let screenShareActive = $state(false);
let livekitConnectionState = $state<string>('disconnected');
let hasVideoTracks = $state(false);
let videoTrackRevision = $state(0);

// Push-to-talk state
let pttEnabled = $state(false);
let pttKey = $state<string | null>(null);
let pttActive = $state(false);
let screenSharePreset = $state<ScreenSharePreset>('auto');

// Latency polling
let voiceLatencyMs = $state(0);
let latencyInterval: ReturnType<typeof setInterval> | null = null;

// Speaking holdover — keep users in speaking set briefly after LiveKit drops them
const speakingHoldTimers = new Map<string, ReturnType<typeof setTimeout>>();

// --- beforeunload handler ---
if (typeof window !== 'undefined') {
	window.addEventListener('beforeunload', () => {
		if (currentVoiceChannelId || joiningChannelId) {
			leaveVoiceBeacon();
		}
	});
}

// --- Getters ---

export function getVoiceStates(): Map<string, VoiceState[]> {
	return voiceStates;
}

export function getVoiceStatesForChannel(channelId: string): VoiceState[] {
	return voiceStates.get(channelId) ?? [];
}

export function getCurrentVoiceChannelId(): string | null {
	return currentVoiceChannelId;
}

export function getJoiningChannelId(): string | null {
	return joiningChannelId;
}

export function isMicPermissionDenied(): boolean {
	return micPermissionDenied;
}

export function isLocalAudioMuted(): boolean {
	return localAudioMuted;
}

export function isLocalDeafened(): boolean {
	return localDeafened;
}

export function isVoiceVideoEnabled(): boolean {
	return voiceVideoEnabled;
}

export function getVoiceMode(): string {
	return voiceMode;
}

export function setVoiceMode(mode: string) {
	voiceMode = mode;
}

export function getCurrentEncryptionKey(): string | null {
	return currentEncryptionKey;
}

export function getCurrentKeyIndex(): number {
	return currentKeyIndex;
}

export function isSpeaking(userId: string): boolean {
	return speakingUserIds.has(userId);
}

export function getSpeakingUserIds(): Set<string> {
	return speakingUserIds;
}

export function getAudioLevel(userId: string): number {
	return audioLevels.get(userId) ?? 0;
}

export function getConnectedParticipantIds(): Set<string> {
	return connectedParticipantIds;
}

export function getParticipantVideoTrack(userId: string) {
	return lkGetParticipantVideoTrack(userId);
}

export function getParticipantScreenTrack(userId: string) {
	return lkGetParticipantScreenTrack(userId);
}

export function getVideoTrackRevision(): number {
	return videoTrackRevision;
}

export function isCameraActive(): boolean {
	return cameraActive;
}

export function isScreenShareActive(): boolean {
	return screenShareActive;
}

export function getLivekitConnectionState(): string {
	return livekitConnectionState;
}

export function getHasVideoTracks(): boolean {
	return hasVideoTracks;
}

export function isPttEnabled(): boolean {
	return pttEnabled;
}

export function getPttKey(): string | null {
	return pttKey;
}

export function isPttActive(): boolean {
	return pttActive;
}

export function getVoiceLatencyMs(): number {
	return voiceLatencyMs;
}

export function getScreenSharePreset(): ScreenSharePreset {
	return screenSharePreset;
}

export function setScreenSharePreset(preset: ScreenSharePreset) {
	screenSharePreset = preset;
}

export function isServerMuted(): boolean {
	return serverMuted;
}

export function isServerDeafened(): boolean {
	return serverDeafened;
}

export function isE2eeActive(): boolean {
	return e2eeActive;
}

export function setE2eeActive(active: boolean) {
	e2eeActive = active;
}

export function isE2eeCapability(): boolean {
	return e2eeCapability;
}

export function setE2eeCapability(enabled: boolean) {
	e2eeCapability = enabled;
}

// --- Setters / Hydration ---

export function setVoiceVideoEnabled(enabled: boolean) {
	voiceVideoEnabled = enabled;
}

export function hydrateVoiceStates(states: VoiceState[]) {
	const map = new Map<string, VoiceState[]>();
	for (const s of states) {
		const list = map.get(s.channel_id) ?? [];
		list.push(s);
		map.set(s.channel_id, list);
	}
	voiceStates = map;
	// Do NOT set currentVoiceChannelId from server state — only populate
	// the voiceStates map so other users appear in channels.
	// currentVoiceChannelId is set locally by joinVoice() / webhook event
	// only when this device has an active LiveKit connection.
	// This also fixes multi-device: device B sees users in voice but
	// doesn't show its own voice bar because it never called joinVoice().
}

// --- Event Handlers ---

export function handleVoiceStateUpdate(data: Record<string, unknown>) {
	// Server sends { channel_id, voice_states: [...] } — full state for the channel
	const channelId = data.channel_id as string;
	const states = data.voice_states as VoiceState[] | undefined;
	if (!channelId) return;

	const next = new Map(voiceStates);
	if (states && states.length > 0) {
		next.set(channelId, states);
	} else {
		next.delete(channelId);
	}
	voiceStates = next;

	// Update local user state
	const me = getUser();
	if (!me) return;
	const myState = states?.find(s => s.user_id === me.id);
	if (myState) {
		// Webhook confirmed we're in this channel — set connected state
		currentVoiceChannelId = myState.channel_id;
		if (joiningChannelId === myState.channel_id) {
			joiningChannelId = null;
		}
		serverMuted = myState.server_mute;
		serverDeafened = myState.server_deaf;
	} else if (currentVoiceChannelId === channelId) {
		// We were in this channel but are no longer in the state list — we left
		currentVoiceChannelId = null;
		localAudioMuted = false;
		localDeafened = false;
		serverMuted = false;
		serverDeafened = false;
		currentEncryptionKey = null;
	}
}

export async function handleVoiceMove(data: Record<string, unknown>) {
	const channelId = data.channel_id as string;
	const token = data.token as string | undefined;
	const url = data.url as string | undefined;
	const encryptionKey = data.encryption_key as string | undefined;
	const keyIdx = data.key_index as number | undefined;

	if (channelId) {
		currentVoiceChannelId = channelId;
	}
	if (encryptionKey) {
		currentEncryptionKey = encryptionKey;
		currentKeyIndex = keyIdx ?? 0;
	}

	// Reconnect to new LiveKit room if token provided
	if (token && url) {
		try {
			await connectToRoom(url, token, encryptionKey, keyIdx ?? 0, makeLiveKitCallbacks());
			if (localAudioMuted) await setMicrophoneEnabled(false);
		} catch (e) {
			console.error('[Voice] Failed to reconnect after move', e);
		}
	}
}

export async function handleVoiceKeyRotate(data: Record<string, unknown>) {
	const channelId = data.channel_id as string;
	if (channelId && channelId === currentVoiceChannelId) {
		const newKey = data.encryption_key as string;
		const newIndex = data.key_index as number;
		currentEncryptionKey = newKey;
		currentKeyIndex = newIndex;
		// Update LiveKit E2EE key
		await updateEncryptionKey(newKey);
	}
}

// --- Actions ---

// Track last join error for UI display
let lastJoinError = $state<string | null>(null);

export function getLastJoinError(): string | null {
	return lastJoinError;
}

export async function joinVoice(channelId: string) {
	lastJoinError = null;
	micPermissionDenied = false;
	joiningChannelId = channelId;
	try {
		const resp = await getVoiceToken(channelId);
		currentEncryptionKey = resp.encryption_key ?? null;
		currentKeyIndex = resp.key_index ?? 0;

		await connectToRoom(
			resp.url,
			resp.token,
			resp.encryption_key,
			resp.key_index,
			makeLiveKitCallbacks()
		);

		// joiningChannelId stays set until webhook VOICE_STATE_UPDATE arrives
		startLatencyPolling();
		return resp;
	} catch (e: any) {
		joiningChannelId = null;
		currentVoiceChannelId = null;
		currentEncryptionKey = null;
		// Best-effort cleanup of any partial server state
		leaveVoiceApi().catch(() => {});
		lastJoinError = e?.message || 'Voice unavailable';
		throw e;
	}
}

export async function leaveCurrentVoice() {
	if (!currentVoiceChannelId && !joiningChannelId) return;

	joiningChannelId = null;
	stopLatencyPolling();
	// Disconnect from LiveKit first
	await disconnectFromRoom(false);
	speakingUserIds = new Set();
	audioLevels = new Map();
	connectedParticipantIds = new Set();
	cameraActive = false;
	screenShareActive = false;
	hasVideoTracks = false;
	videoTrackRevision = 0;
	livekitConnectionState = 'disconnected';

	try {
		await leaveVoiceApi();
	} catch {
		// ignore — server might already have cleaned up
	}
	currentVoiceChannelId = null;
	localAudioMuted = false;
	localDeafened = false;
	serverMuted = false;
	serverDeafened = false;
	currentEncryptionKey = null;
}

export async function toggleMute() {
	if (serverMuted) return; // server-muted — cannot override
	const newMuted = !localAudioMuted;
	localAudioMuted = newMuted;
	try {
		await setMicrophoneEnabled(!newMuted);
		await updateVoiceStateApi({ channel_id: currentVoiceChannelId ?? undefined, self_mute: newMuted });
	} catch {
		localAudioMuted = !newMuted;
		await setMicrophoneEnabled(newMuted).catch(() => {});
	}
}

export async function toggleDeafen() {
	if (serverDeafened) return; // server-deafened — cannot override
	const newDeafened = !localDeafened;
	localDeafened = newDeafened;
	if (newDeafened && !localAudioMuted) {
		localAudioMuted = true;
	}
	try {
		await setMicrophoneEnabled(!newDeafened && !localAudioMuted);
		await updateVoiceStateApi({ channel_id: currentVoiceChannelId ?? undefined, self_deaf: newDeafened, self_mute: newDeafened || localAudioMuted });
	} catch {
		localDeafened = !newDeafened;
		if (newDeafened) localAudioMuted = false;
		await setMicrophoneEnabled(true).catch(() => {});
	}
}

export async function toggleCamera() {
	const newEnabled = !cameraActive;
	try {
		await lkSetCamera(newEnabled);
		cameraActive = lkIsCameraEnabled();
		updateVideoTrackState();
	} catch {
		console.warn('[Voice] Failed to toggle camera');
	}
}

export async function toggleScreenShare() {
	const newEnabled = !screenShareActive;
	try {
		await lkSetScreenShare(newEnabled, screenSharePreset);
		screenShareActive = lkIsScreenShareEnabled();
		updateVideoTrackState();
	} catch {
		console.warn('[Voice] Failed to toggle screen share');
	}
}

export async function applyScreenSharePreset(preset: ScreenSharePreset) {
	screenSharePreset = preset;
	try {
		if (screenShareActive) {
			await lkUpdateScreenShareEncoding(preset);
		} else {
			await lkSetScreenShare(true, preset);
			screenShareActive = lkIsScreenShareEnabled();
			updateVideoTrackState();
		}
	} catch {
		screenShareActive = lkIsScreenShareEnabled();
		updateVideoTrackState();
	}
}

// --- Push-to-talk ---

export function setPttEnabled(enabled: boolean) {
	pttEnabled = enabled;
	if (!enabled) {
		pttActive = false;
	}
}

export function setPttKey(key: string | null) {
	pttKey = key;
}

export async function pttKeyDown() {
	if (!pttEnabled || pttActive || !currentVoiceChannelId) return;
	pttActive = true;
	await setMicrophoneEnabled(true);
}

export async function pttKeyUp() {
	if (!pttEnabled || !pttActive) return;
	pttActive = false;
	await setMicrophoneEnabled(false);
}

export function resetVoiceState() {
	stopLatencyPolling();
	voiceStates = new Map();
	currentVoiceChannelId = null;
	joiningChannelId = null;
	localAudioMuted = false;
	localDeafened = false;
	serverMuted = false;
	serverDeafened = false;
	voiceVideoEnabled = false;
	voiceMode = 'off';
	currentEncryptionKey = null;
	currentKeyIndex = 0;
	e2eeActive = false;
	e2eeCapability = false;
	micPermissionDenied = false;
	lastJoinError = null;
	for (const t of speakingHoldTimers.values()) clearTimeout(t);
	speakingHoldTimers.clear();
	speakingUserIds = new Set();
	audioLevels = new Map();
	connectedParticipantIds = new Set();
	cameraActive = false;
	screenShareActive = false;
	hasVideoTracks = false;
	videoTrackRevision = 0;
	livekitConnectionState = 'disconnected';
	pttEnabled = false;
	pttKey = null;
	pttActive = false;
	voiceLatencyMs = 0;
	disconnectFromRoom(false).catch(() => {});
}

// --- LiveKit callbacks factory ---

function makeLiveKitCallbacks(): LiveKitCallbacks {
	return {
		onSpeakersChanged: (speakers) => {
			const activeSpeakers = new Set(speakers.map((s) => s.identity));
			const newLevels = new Map<string, number>();
			for (const s of speakers) newLevels.set(s.identity, s.audioLevel);

			const merged = new Set(activeSpeakers);

			// Clear holdover timers for anyone speaking again
			for (const id of activeSpeakers) {
				const timer = speakingHoldTimers.get(id);
				if (timer) { clearTimeout(timer); speakingHoldTimers.delete(id); }
			}

			// Hold users who just stopped — decay their level, remove after 800ms
			for (const id of speakingUserIds) {
				if (!activeSpeakers.has(id)) {
					merged.add(id);
					newLevels.set(id, (audioLevels.get(id) ?? 0) * 0.35);
					if (!speakingHoldTimers.has(id)) {
						speakingHoldTimers.set(id, setTimeout(() => {
							speakingHoldTimers.delete(id);
							const next = new Set(speakingUserIds);
							next.delete(id);
							speakingUserIds = next;
							const nextLevels = new Map(audioLevels);
							nextLevels.delete(id);
							audioLevels = nextLevels;
						}, 800));
					}
				}
			}

			speakingUserIds = merged;
			audioLevels = newLevels;
		},
		onParticipantConnected: (identity) => {
			connectedParticipantIds = new Set([...connectedParticipantIds, identity]);
		},
		onParticipantDisconnected: (identity) => {
			const next = new Set(connectedParticipantIds);
			next.delete(identity);
			connectedParticipantIds = next;
		},
		onLocalTrackPublished: () => updateVideoTrackState(),
		onLocalTrackUnpublished: () => updateVideoTrackState(),
		onTrackSubscribed: (track, _pub, _participant) => {
			if (track.kind === Track.Kind.Audio) {
				const el = track.attach();
				el.style.display = 'none';
				document.body.appendChild(el);
			}
			updateVideoTrackState();
		},
		onTrackUnsubscribed: (track) => {
			track.detach().forEach((el) => el.remove());
			updateVideoTrackState();
		},
		onConnectionStateChanged: (state) => {
			livekitConnectionState = state;
			// LiveKit confirmed connection — transition from joining to connected
			if (state === ConnectionState.Connected && joiningChannelId) {
				currentVoiceChannelId = joiningChannelId;
				joiningChannelId = null;
			}
			// Unexpected disconnect — LiveKit dropped without user calling leave
			if (state === ConnectionState.Disconnected && (currentVoiceChannelId || joiningChannelId)) {
				joiningChannelId = null;
				currentVoiceChannelId = null;
				stopLatencyPolling();
				leaveVoiceApi().catch(() => {});
			}
		},
		onMicPermissionDenied: () => {
			micPermissionDenied = true;
			localAudioMuted = true;
		},
		onE2EEStateChanged: (_identity, state) => {
			e2eeActive = state === 'enabled';
			// 'disabled' means server didn't send key — not a failure
		}
	};
}

function updateVideoTrackState() {
	const remote = getVideoTracks();
	const local = getLocalVideoTracks();
	hasVideoTracks = remote.length > 0 || local.length > 0;
	videoTrackRevision++;
}

// --- Latency polling ---

function startLatencyPolling() {
	stopLatencyPolling();
	voiceLatencyMs = Math.round(getSignalRtt());
	latencyInterval = setInterval(() => {
		voiceLatencyMs = Math.round(getSignalRtt());
	}, 2000);
}

function stopLatencyPolling() {
	if (latencyInterval) {
		clearInterval(latencyInterval);
		latencyInterval = null;
	}
	voiceLatencyMs = 0;
}
