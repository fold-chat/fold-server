<script lang="ts">
	import { uploadFile } from '$lib/api/upload.js';
	import type { ApiError } from '$lib/api/client.js';

	let {
		name = $bindable(''),
		description = $bindable(''),
		icon = $bindable(''),
		serverUrl = $bindable(''),
		showUrl = false,
		onsave,
		saving = false,
		error = $bindable(''),
		success = $bindable('')
	}: {
		name: string;
		description: string;
		icon: string;
		serverUrl?: string;
		showUrl?: boolean;
		onsave: () => void;
		saving?: boolean;
		error: string;
		success: string;
	} = $props();

	let uploadingIcon = $state(false);

	async function handleIconUpload(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;

		if (!file.type.startsWith('image/')) {
			error = 'Icon must be an image';
			return;
		}

		uploadingIcon = true;
		error = '';
		try {
			const result = await uploadFile(file);
			icon = result.url;
		} catch (err) {
			error = (err as ApiError).message || 'Failed to upload icon';
		} finally {
			uploadingIcon = false;
			input.value = '';
		}
	}

	function removeIcon() {
		icon = '';
	}
</script>

<div class="form-section">
	<div class="form-group">
		<label for="serverName">Server Name</label>
		<input id="serverName" type="text" bind:value={name} maxlength="100" />
	</div>

	<div class="form-group">
		<label for="serverDesc">Description</label>
		<textarea id="serverDesc" bind:value={description} maxlength="500" rows="3"
			placeholder="A short description of your community"></textarea>
		<span class="char-count">{description.length}/500</span>
	</div>

	<div class="form-group">
		<!-- svelte-ignore a11y_label_has_associated_control -->
		<label>Server Icon</label>
		<div class="icon-section">
			{#if icon}
				<img src={icon} alt="Server icon" class="icon-preview" />
				<button class="btn-sm btn-danger" onclick={removeIcon}>Remove</button>
			{:else}
				<div class="icon-placeholder">No icon</div>
			{/if}
			<label class="btn-sm upload-btn">
				{uploadingIcon ? 'Uploading...' : 'Upload Icon'}
				<input type="file" accept="image/*" onchange={handleIconUpload} hidden disabled={uploadingIcon} />
			</label>
		</div>
	</div>

	{#if showUrl}
		<div class="form-group">
			<label for="serverUrl">Server URL</label>
			<input id="serverUrl" type="text" bind:value={serverUrl} placeholder="https://your-server.example.com" />
			<span class="hint">Public URL where users will access this server.</span>
		</div>
	{/if}

	<div class="form-actions">
		<button class="btn-primary" onclick={onsave} disabled={saving || !name.trim()}>
			{saving ? 'Saving...' : 'Save Changes'}
		</button>
	</div>
</div>

<style>
	.char-count {
		font-size: 0.7rem;
		color: var(--text-muted);
		text-align: right;
	}

	.icon-section {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.icon-preview {
		width: 64px;
		height: 64px;
		border-radius: 16px;
		object-fit: cover;
		border: 2px solid var(--border);
	}

	.icon-placeholder {
		width: 64px;
		height: 64px;
		border-radius: 16px;
		background: var(--bg);
		border: 2px dashed var(--border);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.upload-btn {
		cursor: pointer;
	}

	.hint {
		font-size: 0.75rem;
		color: var(--text-muted);
		margin-top: 0.15rem;
	}
</style>
