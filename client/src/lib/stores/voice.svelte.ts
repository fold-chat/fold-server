import type { VoiceState } from '$lib/api/voice.js';
import { getVoiceToken, leaveVoice as leaveVoiceApi, updateVoiceState as updateVoiceStateApi } from '$lib/api/voice.js';
import { getUser } from '$lib/stores/auth.svelte.js';

// --- State ---

let voiceStates = $state<Map<string, VoiceState[]>>(new Map());
let currentVoiceChannelId = $state<string | null>(null);
let localAudioMuted = $state(false);
let localDeafened = $state(false);
let voiceVideoEnabled = $state(false);

// E2EE key state (used by Phase 4 LiveKit integration)
let currentEncryptionKey = $state<string | null>(null);
let currentKeyIndex = $state<number>(0);

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

export function handleVoiceMove(data: Record<string, unknown>) {
	// Targeted at the moved user — contains new token + URL
	// Phase 4 will handle the actual LiveKit reconnection
	const channelId = data.channel_id as string;
	if (channelId) {
		currentVoiceChannelId = channelId;
	}
	if (data.encryption_key) {
		currentEncryptionKey = data.encryption_key as string;
		currentKeyIndex = data.key_index as number;
	}
}

export function handleVoiceKeyRotate(data: Record<string, unknown>) {
	const channelId = data.channel_id as string;
	if (channelId && channelId === currentVoiceChannelId) {
		currentEncryptionKey = data.encryption_key as string;
		currentKeyIndex = data.key_index as number;
	}
}

// --- Actions ---

export async function joinVoice(channelId: string) {
	try {
		const resp = await getVoiceToken(channelId);
		currentVoiceChannelId = channelId;
		currentEncryptionKey = resp.encryption_key;
		currentKeyIndex = resp.key_index;
		// Phase 4: connect to LiveKit with resp.token + resp.url
		return resp;
	} catch (e) {
		throw e;
	}
}

export async function leaveCurrentVoice() {
	if (!currentVoiceChannelId) return;
	try {
		await leaveVoiceApi();
	} catch {
		// ignore — server might already have cleaned up
	}
	// Phase 4: disconnect from LiveKit room
	currentVoiceChannelId = null;
	localAudioMuted = false;
	localDeafened = false;
	currentEncryptionKey = null;
}

export async function toggleMute() {
	const newMuted = !localAudioMuted;
	localAudioMuted = newMuted;
	try {
		await updateVoiceStateApi({ self_mute: newMuted });
	} catch {
		localAudioMuted = !newMuted; // revert
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
		await updateVoiceStateApi({ self_deaf: newDeafened, self_mute: newDeafened || localAudioMuted });
	} catch {
		localDeafened = !newDeafened; // revert
		if (newDeafened) localAudioMuted = false;
	}
}

export function resetVoiceState() {
	voiceStates = new Map();
	currentVoiceChannelId = null;
	localAudioMuted = false;
	localDeafened = false;
	voiceVideoEnabled = false;
	currentEncryptionKey = null;
	currentKeyIndex = 0;
}
