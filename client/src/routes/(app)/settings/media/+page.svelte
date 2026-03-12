<script lang="ts">
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { getRuntimeConfig, updateRuntimeConfig, type RuntimeConfig } from '$lib/api/config.js';

	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));

	let config = $state<RuntimeConfig>({});
	let loading = $state(false);
	let saving = $state(false);
	let error = $state('');
	let success = $state('');

	// Editable fields
	let editVideoMode = $state('no-transcode');
	let editMaxVideoDuration = $state('300');
	let editMaxVideoSize = $state('104857600');
	let editMaxImageSize = $state('20971520');
	let editThumbnailMaxWidth = $state('400');
	let editHwAccel = $state('auto');

	function hydrateFields(cfg: RuntimeConfig) {
		editVideoMode = cfg['kith.media-processing.video-mode'] ?? 'no-transcode';
		editMaxVideoDuration = cfg['kith.media-processing.max-video-duration'] ?? '300';
		editMaxVideoSize = cfg['kith.media-processing.max-video-size'] ?? '104857600';
		editMaxImageSize = cfg['kith.media-processing.max-image-size'] ?? '20971520';
		editThumbnailMaxWidth = cfg['kith.media-processing.thumbnail-max-width'] ?? '400';
		editHwAccel = cfg['kith.media-processing.hw-accel'] ?? 'auto';
	}

	const hasChanges = $derived.by(() => {
		const cfg = config;
		if (editVideoMode !== (cfg['kith.media-processing.video-mode'] ?? 'no-transcode')) return true;
		if (editMaxVideoDuration !== (cfg['kith.media-processing.max-video-duration'] ?? '300')) return true;
		if (editMaxVideoSize !== (cfg['kith.media-processing.max-video-size'] ?? '104857600')) return true;
		if (editMaxImageSize !== (cfg['kith.media-processing.max-image-size'] ?? '20971520')) return true;
		if (editThumbnailMaxWidth !== (cfg['kith.media-processing.thumbnail-max-width'] ?? '400')) return true;
		if (editHwAccel !== (cfg['kith.media-processing.hw-accel'] ?? 'auto')) return true;
		return false;
	});

	function formatBytes(bytes: string): string {
		const n = parseInt(bytes);
		if (isNaN(n)) return bytes;
		if (n < 1024 * 1024) return (n / 1024).toFixed(0) + ' KB';
		return (n / (1024 * 1024)).toFixed(0) + ' MB';
	}

	async function loadAll() {
		loading = true;
		error = '';
		try {
			const cfg = await getRuntimeConfig();
			config = cfg;
			hydrateFields(cfg);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to load media settings';
		} finally {
			loading = false;
		}
	}

	async function save() {
		saving = true;
		error = '';
		success = '';
		try {
			const patch: RuntimeConfig = {
				'kith.media-processing.video-mode': editVideoMode,
				'kith.media-processing.max-video-duration': editMaxVideoDuration,
				'kith.media-processing.max-video-size': editMaxVideoSize,
				'kith.media-processing.max-image-size': editMaxImageSize,
				'kith.media-processing.thumbnail-max-width': editThumbnailMaxWidth,
				'kith.media-processing.hw-accel': editHwAccel
			};
			const result = await updateRuntimeConfig(patch);
			config = result;
			hydrateFields(result);
			success = 'Settings saved';
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save';
		} finally {
			saving = false;
			setTimeout(() => { success = ''; }, 3000);
		}
	}

	$effect(() => {
		if (canManageServer) loadAll();
	});
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Media Processing</h1>
		<button class="btn-sm" onclick={loadAll} disabled={loading}>
			{loading ? 'Loading...' : 'Refresh'}
		</button>
	</div>

	{#if !canManageServer}
		<p class="muted">You don't have permission to manage media settings.</p>
	{:else}
		{#if error}
			<div class="error-message">{error}</div>
		{/if}
		{#if success}
			<div class="success-message">{success}</div>
		{/if}

		<div class="form-section">
			<h3 class="subsection">Video Uploads</h3>
			<div class="form-group">
				<label for="video-mode">Video Mode</label>
				<select id="video-mode" bind:value={editVideoMode}>
					<option value="disabled">Disabled</option>
					<option value="no-transcode">No Transcode (store as-is)</option>
					<option value="transcode">Transcode to MP4 (H.264)</option>
				</select>
				<span class="hint">
					{#if editVideoMode === 'disabled'}Videos are rejected on upload.
					{:else if editVideoMode === 'no-transcode'}Videos stored as-is. Browser plays natively or shows error.
					{:else}Videos transcoded to MP4 for universal playback. Requires ffmpeg.
					{/if}
				</span>
			</div>

			{#if editVideoMode !== 'disabled'}
				<div class="form-group">
					<label for="max-video-duration">Max Video Duration (seconds)</label>
					<input id="max-video-duration" type="number" bind:value={editMaxVideoDuration} min="1" max="3600" />
					<span class="hint">{Math.floor(parseInt(editMaxVideoDuration) / 60)}m {parseInt(editMaxVideoDuration) % 60}s</span>
				</div>
				<div class="form-group">
					<label for="max-video-size">Max Video Size (bytes)</label>
					<input id="max-video-size" type="number" bind:value={editMaxVideoSize} min="1048576" />
					<span class="hint">{formatBytes(editMaxVideoSize)}</span>
				</div>
			{/if}

			{#if editVideoMode === 'transcode'}
				<div class="form-group">
					<label for="hw-accel">Hardware Acceleration</label>
					<select id="hw-accel" bind:value={editHwAccel}>
						<option value="auto">Auto-detect</option>
						<option value="none">None (software)</option>
						<option value="videotoolbox">VideoToolbox (macOS)</option>
						<option value="nvenc">NVENC (NVIDIA)</option>
						<option value="vaapi">VA-API (Linux)</option>
						<option value="qsv">QSV (Intel)</option>
					</select>
				</div>
			{/if}
		</div>

		<div class="form-section" style="margin-top: 1rem">
			<h3 class="subsection">Images</h3>
			<div class="form-group">
				<label for="max-image-size">Max Image Size (bytes)</label>
				<input id="max-image-size" type="number" bind:value={editMaxImageSize} min="1048576" />
				<span class="hint">{formatBytes(editMaxImageSize)}</span>
			</div>
			<div class="form-group">
				<label for="thumbnail-max-width">Thumbnail Max Width (pixels)</label>
				<input id="thumbnail-max-width" type="number" bind:value={editThumbnailMaxWidth} min="100" max="1920" />
			</div>
		</div>

		{#if hasChanges}
			<div class="form-actions" style="margin-top: 1rem">
				<button class="btn-primary" onclick={save} disabled={saving}>
					{saving ? 'Saving...' : 'Save'}
				</button>
			</div>
		{/if}
	{/if}
</div>

<style>
	.subsection {
		font-size: 0.85rem;
		margin: 0 0 0.35rem;
		color: var(--text-muted);
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.03em;
	}

	.hint {
		font-size: 0.75rem;
		color: var(--text-muted);
		margin-top: 0.15rem;
	}

	select {
		width: 100%;
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.5rem 0.6rem;
		font-size: 0.875rem;
		font-family: inherit;
	}
</style>
