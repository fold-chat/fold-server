export interface MediaDeviceInfo_ {
	deviceId: string;
	label: string;
	groupId: string;
}

const AUDIO_INPUT_KEY = 'kith_audio_input';
const VIDEO_INPUT_KEY = 'kith_video_input';
const AUDIO_OUTPUT_KEY = 'kith_audio_output';
const AUDIO_PROCESSING_KEY = 'kith_audio_processing';

// --- State ---

let audioInputs = $state<MediaDeviceInfo_[]>([]);
let videoInputs = $state<MediaDeviceInfo_[]>([]);
let audioOutputs = $state<MediaDeviceInfo_[]>([]);

let selectedAudioInputId = $state<string | null>(null);
let selectedVideoInputId = $state<string | null>(null);
let selectedAudioOutputId = $state<string | null>(null);
let audioProcessing = $state(false);

// Callbacks for mid-call switching (set by voice.svelte.ts)
let onAudioInputChanged: ((id: string) => void) | null = null;
let onVideoInputChanged: ((id: string) => void) | null = null;
let onAudioOutputChanged: ((id: string) => void) | null = null;

// --- Getters ---

export function getAudioInputs(): MediaDeviceInfo_[] {
	return audioInputs;
}

export function getVideoInputs(): MediaDeviceInfo_[] {
	return videoInputs;
}

export function getAudioOutputs(): MediaDeviceInfo_[] {
	return audioOutputs;
}

export function getSelectedAudioInputId(): string | null {
	return selectedAudioInputId;
}

export function getSelectedVideoInputId(): string | null {
	return selectedVideoInputId;
}

export function getSelectedAudioOutputId(): string | null {
	return selectedAudioOutputId;
}

export function getAudioProcessing(): boolean {
	return audioProcessing;
}

// --- Setters ---

export function setAudioInput(id: string) {
	selectedAudioInputId = id;
	localStorage.setItem(AUDIO_INPUT_KEY, id);
	onAudioInputChanged?.(id);
}

export function setVideoInput(id: string) {
	selectedVideoInputId = id;
	localStorage.setItem(VIDEO_INPUT_KEY, id);
	onVideoInputChanged?.(id);
}

export function setAudioOutput(id: string) {
	selectedAudioOutputId = id;
	localStorage.setItem(AUDIO_OUTPUT_KEY, id);
	onAudioOutputChanged?.(id);
}

export function setAudioProcessing(enabled: boolean) {
	audioProcessing = enabled;
	localStorage.setItem(AUDIO_PROCESSING_KEY, enabled ? '1' : '0');
}

// --- Mid-call switch callbacks ---

export function registerDeviceCallbacks(cbs: {
	onAudioInput?: (id: string) => void;
	onVideoInput?: (id: string) => void;
	onAudioOutput?: (id: string) => void;
}) {
	onAudioInputChanged = cbs.onAudioInput ?? null;
	onVideoInputChanged = cbs.onVideoInput ?? null;
	onAudioOutputChanged = cbs.onAudioOutput ?? null;
}

export function clearDeviceCallbacks() {
	onAudioInputChanged = null;
	onVideoInputChanged = null;
	onAudioOutputChanged = null;
}

// --- Enumerate ---

export async function enumerateDevices(): Promise<void> {
	if (typeof navigator === 'undefined' || !navigator.mediaDevices) return;
	try {
		const devices = await navigator.mediaDevices.enumerateDevices();
		audioInputs = devices
			.filter((d) => d.kind === 'audioinput')
			.map((d) => ({ deviceId: d.deviceId, label: d.label || `Mic ${d.deviceId.slice(0, 6)}`, groupId: d.groupId }));
		videoInputs = devices
			.filter((d) => d.kind === 'videoinput')
			.map((d) => ({ deviceId: d.deviceId, label: d.label || `Camera ${d.deviceId.slice(0, 6)}`, groupId: d.groupId }));
		audioOutputs = devices
			.filter((d) => d.kind === 'audiooutput')
			.map((d) => ({ deviceId: d.deviceId, label: d.label || `Speaker ${d.deviceId.slice(0, 6)}`, groupId: d.groupId }));
	} catch {
		/* permission denied or unavailable */
	}
}

// --- Init ---

export function initDevices() {
	if (typeof localStorage === 'undefined') return;
	try {
		selectedAudioInputId = localStorage.getItem(AUDIO_INPUT_KEY);
		selectedVideoInputId = localStorage.getItem(VIDEO_INPUT_KEY);
		selectedAudioOutputId = localStorage.getItem(AUDIO_OUTPUT_KEY);
		audioProcessing = localStorage.getItem(AUDIO_PROCESSING_KEY) === '1';
	} catch { /* ignore */ }

	enumerateDevices();

	if (typeof navigator !== 'undefined' && navigator.mediaDevices) {
		navigator.mediaDevices.ondevicechange = () => enumerateDevices();
	}
}
