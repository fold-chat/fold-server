import type { VoiceState } from '$lib/api/voice.js';
import { getVoiceToken, leaveVoice as leaveVoiceApi, updateVoiceState as updateVoiceStateApi } from '$lib/api/voice.js';
import { getUser } from '$lib/stores/auth.svelte.js';
import {
	connectToRoom,
	disconnectFromRoom,
	updateEncryptionKey,
	setMicrophoneEnabled,
	setCameraEnabled as lkSetCamera,
	setScreenShareEnabled as lkSetScreenShare,
	isCameraEnabled as lkIsCameraEnabled,
	isScreenShareEnabled as lkIsScreenShareEnabled,
	getVideoTracks,
	getLocalVideoTracks,
	type LiveKitCallbacks
} from '$lib/voice/livekit.js';
import { Track, ConnectionState } from 'livekit-client';

// --- State ---

let voiceStates = $state<Map<string, VoiceState[]>>(new Map());
let currentVoiceChannelId = $state<string | null>(null);
let localAudioMuted = $state(false);
let localDeafened = $state(false);
let voiceVideoEnabled = $state(false);

// E2EE key state
let currentEncryptionKey = $state<string | null>(null);
let currentKeyIndex = $state<number>(0);

// LiveKit integration state
let speakingUserIds = $state<Set<string>>(new Set());
let cameraActive = $state(false);
let screenShareActive = $state(false);
let livekitConnectionState = $state<string>('disconnected');
let hasVideoTracks = $state(false);

// Push-to-talk state
let pttEnabled = $state(false);
let pttKey = $state<string | null>(null);
let pttActive = $state(false);

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

export function isLocalAudioMuted(): boolean {
	return localAudioMuted;
}

export function isLocalDeafened(): boolean {
	return localDeafened;
}

export function isVoiceVideoEnabled(): boolean {
	return voiceVideoEnabled;
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

	// Check if local user is in a voice channel
	const me = getUser();
	if (me) {
		const myState = states.find(s => s.user_id === me.id);
		if (myState) {
			currentVoiceChannelId = myState.channel_id;
			localAudioMuted = myState.self_mute;
			localDeafened = myState.self_deaf;
		}
	}
}

// --- Event Handlers ---

export function handleVoiceStateUpdate(data: Record<string, unknown>) {
	const state = data as unknown as VoiceState;
	if (!state.channel_id || !state.user_id) return;

	const removed = data.removed as boolean | undefined;

	if (removed) {
		// User left voice
		const channelStates = voiceStates.get(state.channel_id) ?? [];
		const filtered = channelStates.filter(s => s.user_id !== state.user_id);
		const next = new Map(voiceStates);
		if (filtered.length === 0) {
			next.delete(state.channel_id);
		} else {
			next.set(state.channel_id, filtered);
		}
		voiceStates = next;

		// If it's us, clear local state
		const me = getUser();
		if (me && state.user_id === me.id) {
			currentVoiceChannelId = null;
			localAudioMuted = false;
			localDeafened = false;
			currentEncryptionKey = null;
		}
	} else {
		// User joined or updated
		const next = new Map(voiceStates);
		const channelStates = next.get(state.channel_id) ?? [];
		const idx = channelStates.findIndex(s => s.user_id === state.user_id);

		// Also remove from previous channel if they moved
		if (data.previous_channel_id) {
			const prevId = data.previous_channel_id as string;
			const prevStates = (next.get(prevId) ?? []).filter(s => s.user_id !== state.user_id);
			if (prevStates.length === 0) next.delete(prevId);
			else next.set(prevId, prevStates);
		}

		if (idx >= 0) {
			channelStates[idx] = state;
			next.set(state.channel_id, [...channelStates]);
		} else {
			next.set(state.channel_id, [...channelStates, state]);
		}
		voiceStates = next;

		// Update local state if it's us
		const me = getUser();
		if (me && state.user_id === me.id) {
			currentVoiceChannelId = state.channel_id;
			localAudioMuted = state.self_mute;
			localDeafened = state.self_deaf;
		}
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
	if (token && url && encryptionKey) {
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

export async function joinVoice(channelId: string) {
	try {
		const resp = await getVoiceToken(channelId);
		currentVoiceChannelId = channelId;
		currentEncryptionKey = resp.encryption_key;
		currentKeyIndex = resp.key_index;

		// Connect to LiveKit room with E2EE
		await connectToRoom(
			resp.url,
			resp.token,
			resp.encryption_key,
			resp.key_index,
			makeLiveKitCallbacks()
		);

		return resp;
	} catch (e) {
		// If LiveKit connect fails, revert state
		currentVoiceChannelId = null;
		currentEncryptionKey = null;
		throw e;
	}
}

export async function leaveCurrentVoice() {
	if (!currentVoiceChannelId) return;

	// Disconnect from LiveKit first
	await disconnectFromRoom(false);
	speakingUserIds = new Set();
	cameraActive = false;
	screenShareActive = false;
	hasVideoTracks = false;
	livekitConnectionState = 'disconnected';

	try {
		await leaveVoiceApi();
	} catch {
		// ignore — server might already have cleaned up
	}
	currentVoiceChannelId = null;
	localAudioMuted = false;
	localDeafened = false;
	currentEncryptionKey = null;
}

export async function toggleMute() {
	const newMuted = !localAudioMuted;
	localAudioMuted = newMuted;
	try {
		// Sync LiveKit mic state
		await setMicrophoneEnabled(!newMuted);
		await updateVoiceStateApi({ self_mute: newMuted });
	} catch {
		localAudioMuted = !newMuted; // revert
		await setMicrophoneEnabled(newMuted).catch(() => {});
	}
}

export async function toggleDeafen() {
	const newDeafened = !localDeafened;
	localDeafened = newDeafened;
	// Deafening also mutes
	if (newDeafened && !localAudioMuted) {
		localAudioMuted = true;
	}
	try {
		// Sync LiveKit mic state
		await setMicrophoneEnabled(!newDeafened && !localAudioMuted);
		await updateVoiceStateApi({ self_deaf: newDeafened, self_mute: newDeafened || localAudioMuted });
	} catch {
		localDeafened = !newDeafened; // revert
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
		await lkSetScreenShare(newEnabled);
		screenShareActive = lkIsScreenShareEnabled();
		updateVideoTrackState();
	} catch {
		console.warn('[Voice] Failed to toggle screen share');
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
	voiceStates = new Map();
	currentVoiceChannelId = null;
	localAudioMuted = false;
	localDeafened = false;
	voiceVideoEnabled = false;
	currentEncryptionKey = null;
	currentKeyIndex = 0;
	speakingUserIds = new Set();
	cameraActive = false;
	screenShareActive = false;
	hasVideoTracks = false;
	livekitConnectionState = 'disconnected';
	pttEnabled = false;
	pttKey = null;
	pttActive = false;
	disconnectFromRoom(false).catch(() => {});
}

// --- LiveKit callbacks factory ---

function makeLiveKitCallbacks(): LiveKitCallbacks {
	return {
		onSpeakersChanged: (ids: string[]) => {
			speakingUserIds = new Set(ids);
		},
		onTrackSubscribed: (track, _pub, _participant) => {
			// Auto-attach audio tracks
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
		}
	};
}

function updateVideoTrackState() {
	const remote = getVideoTracks();
	const local = getLocalVideoTracks();
	hasVideoTracks = remote.length > 0 || local.length > 0;
}
