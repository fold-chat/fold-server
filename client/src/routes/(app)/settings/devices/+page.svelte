<script lang="ts">
	import {
		getAudioInputs,
		getVideoInputs,
		getAudioOutputs,
		getSelectedAudioInputId,
		getSelectedVideoInputId,
		getSelectedAudioOutputId,
		getAudioProcessing,
		setAudioInput,
		setVideoInput,
		setAudioOutput,
		setAudioProcessing,
		enumerateDevices
	} from '$lib/stores/devices.svelte.js';
	import { getCurrentVoiceChannelId } from '$lib/stores/voice.svelte.js';
	import { onMount, tick } from 'svelte';

	const inCall = $derived(getCurrentVoiceChannelId() !== null);

	// --- Mic test (volume meter) ---
	let micStream: MediaStream | null = null;
	let audioCtx: AudioContext | null = null;
	let analyser: AnalyserNode | null = null;
	let micLevel = $state(0);
	let micTestActive = $state(false);
	let micRaf = 0;

	async function startMicTest(deviceId: string | null) {
		stopMicTest();
		try {
			micStream = await navigator.mediaDevices.getUserMedia({
				audio: deviceId ? { deviceId: { ideal: deviceId } } : true
			});
			// Re-enumerate so dropdowns get proper labels after permission grant
			enumerateDevices();
			audioCtx = new AudioContext();
			const source = audioCtx.createMediaStreamSource(micStream);
			analyser = audioCtx.createAnalyser();
			analyser.fftSize = 256;
			source.connect(analyser);
			const data = new Uint8Array(analyser.frequencyBinCount);
			micTestActive = true;
			function poll() {
				if (!analyser) return;
				analyser.getByteFrequencyData(data);
				let sum = 0;
				for (let i = 0; i < data.length; i++) sum += data[i];
				micLevel = sum / data.length / 255;
				micRaf = requestAnimationFrame(poll);
			}
			poll();
		} catch {
			micLevel = 0;
			micTestActive = false;
		}
	}

	function stopMicTest() {
		cancelAnimationFrame(micRaf);
		micStream?.getTracks().forEach((t) => t.stop());
		micStream = null;
		audioCtx?.close().catch(() => {});
		audioCtx = null;
		analyser = null;
		micLevel = 0;
		micTestActive = false;
	}

	function toggleMicTest() {
		if (micTestActive) {
			stopMicTest();
		} else {
			startMicTest(getSelectedAudioInputId());
		}
	}

	// --- Camera preview ---
	let cameraStream: MediaStream | null = null;
	let videoEl: HTMLVideoElement | null = $state(null);
	let cameraPreviewActive = $state(false);

	async function startCameraPreview(deviceId: string | null) {
		stopCameraPreview();
		try {
			cameraStream = await navigator.mediaDevices.getUserMedia({
				video: deviceId ? { deviceId: { ideal: deviceId } } : true
			});
			// Re-enumerate so dropdowns get proper labels after permission grant
			enumerateDevices();
			cameraPreviewActive = true;
			await tick(); // wait for Svelte to render the <video> element
			if (videoEl) {
				videoEl.srcObject = cameraStream;
			}
		} catch {
			cameraPreviewActive = false;
		}
	}

	function stopCameraPreview() {
		cameraStream?.getTracks().forEach((t) => t.stop());
		cameraStream = null;
		if (videoEl) videoEl.srcObject = null;
		cameraPreviewActive = false;
	}

	function toggleCameraPreview() {
		if (cameraPreviewActive) {
			stopCameraPreview();
		} else {
			startCameraPreview(getSelectedVideoInputId());
		}
	}

	// --- setSinkId support check ---
	const sinkIdSupported = typeof HTMLMediaElement !== 'undefined' && 'setSinkId' in HTMLMediaElement.prototype;

	// --- Lifecycle ---
	onMount(() => {
		enumerateDevices();
		return () => {
			stopMicTest();
			stopCameraPreview();
		};
	});

	function handleAudioInputChange(e: Event) {
		const id = (e.target as HTMLSelectElement).value;
		setAudioInput(id);
		if (micTestActive) startMicTest(id);
	}

	function handleVideoInputChange(e: Event) {
		const id = (e.target as HTMLSelectElement).value;
		setVideoInput(id);
		if (cameraPreviewActive) startCameraPreview(id);
	}

	function handleAudioOutputChange(e: Event) {
		const id = (e.target as HTMLSelectElement).value;
		setAudioOutput(id);
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Devices</h1>
	</div>

	<div class="form-section">
		<!-- Microphone -->
		<div class="form-group">
			<label for="mic-select">Microphone</label>
			<select id="mic-select" class="device-select" onchange={handleAudioInputChange}>
				{#each getAudioInputs() as d (d.deviceId)}
					<option value={d.deviceId} selected={d.deviceId === getSelectedAudioInputId()}>{d.label}</option>
				{/each}
				{#if getAudioInputs().length === 0}
					<option disabled>No microphones found</option>
				{/if}
			</select>
			{#if inCall}
				<span class="muted" style="font-size: 0.75rem; margin-top: 0.3rem">Mic test unavailable during a call.</span>
			{:else}
				<button class="btn-sm test-btn" onclick={toggleMicTest}>
					{micTestActive ? 'Stop Mic Test' : 'Test Microphone'}
				</button>
				{#if micTestActive}
					<div class="meter-wrap">
						<div class="meter-bar" style="width: {micLevel * 100}%"></div>
					</div>
					<span class="meter-hint muted">Speak to test your microphone</span>
				{/if}
			{/if}
		</div>

		<!-- Camera -->
		<div class="form-group">
			<label for="cam-select">Camera</label>
			<select id="cam-select" class="device-select" onchange={handleVideoInputChange}>
				{#each getVideoInputs() as d (d.deviceId)}
					<option value={d.deviceId} selected={d.deviceId === getSelectedVideoInputId()}>{d.label}</option>
				{/each}
				{#if getVideoInputs().length === 0}
					<option disabled>No cameras found</option>
				{/if}
			</select>
			{#if inCall}
				<span class="muted" style="font-size: 0.75rem; margin-top: 0.3rem">Camera preview unavailable during a call.</span>
			{:else}
				<button class="btn-sm test-btn" onclick={toggleCameraPreview}>
					{cameraPreviewActive ? 'Stop Preview' : 'Preview Camera'}
				</button>
				{#if cameraPreviewActive}
					<!-- svelte-ignore a11y_media_has_caption -->
					<video
						class="camera-preview"
						autoplay
						playsinline
						muted
						bind:this={videoEl}
					></video>
				{/if}
			{/if}
		</div>

		<!-- Speaker -->
		<div class="form-group">
			<label for="speaker-select">Audio Output</label>
			{#if sinkIdSupported}
				<select id="speaker-select" class="device-select" onchange={handleAudioOutputChange}>
					{#each getAudioOutputs() as d (d.deviceId)}
						<option value={d.deviceId} selected={d.deviceId === getSelectedAudioOutputId()}>{d.label}</option>
					{/each}
					{#if getAudioOutputs().length === 0}
						<option disabled>No audio outputs found</option>
					{/if}
				</select>
			{:else}
				<p class="muted">Audio output selection is not supported in this browser.</p>
			{/if}
		</div>

		<!-- Audio Processing -->
		<div class="form-group">
			<!-- svelte-ignore a11y_label_has_associated_control -->
			<label>Audio Processing</label>
			<label class="toggle-row">
				<input
					type="checkbox"
					checked={getAudioProcessing()}
					onchange={(e) => setAudioProcessing((e.target as HTMLInputElement).checked)}
				/>
				<span>Noise suppression &amp; auto-gain control</span>
			</label>
			<span class="muted" style="font-size: 0.75rem">Browser support varies. Takes effect on next voice join.</span>
		</div>
	</div>
</div>

<style>
	.device-select {
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.5rem 0.6rem;
		font-size: 0.875rem;
		font-family: inherit;
		width: 100%;
		max-width: 100%;
		box-sizing: border-box;
		cursor: pointer;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.device-select:focus {
		outline: none;
		border-color: var(--accent, #5865f2);
	}

	/* Volume meter */
	.meter-wrap {
		height: 6px;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 3px;
		overflow: hidden;
		margin-top: 0.4rem;
	}

	.meter-bar {
		height: 100%;
		background: #2ecc71;
		border-radius: 3px;
		transition: width 0.05s linear;
		min-width: 0;
	}

	.meter-hint {
		font-size: 0.7rem;
		margin-top: 0.15rem;
	}

	/* Camera preview */
	.camera-preview {
		width: 100%;
		max-width: 320px;
		aspect-ratio: 16 / 9;
		border-radius: 8px;
		background: #000;
		margin-top: 0.4rem;
		object-fit: cover;
		display: block;
	}

	/* Test buttons */
	.test-btn {
		margin-top: 0.4rem;
		width: fit-content;
	}

	/* Toggle row */
	.toggle-row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		cursor: pointer;
		text-transform: none;
		font-weight: 400;
		font-size: 0.875rem;
		color: var(--text);
		min-width: 0;
		max-width: 100%;
	}

	.toggle-row span {
		flex: 1;
		min-width: 0;
	}

	.toggle-row input[type='checkbox'] {
		flex: 0 0 auto;
		width: 16px;
		height: 16px;
		accent-color: var(--accent, #5865f2);
		cursor: pointer;
	}
</style>
