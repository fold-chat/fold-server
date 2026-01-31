<script lang="ts">
	import { getServerSettings, setServerSettings } from '$lib/stores/auth.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { updateServerSettings, type ServerSettings } from '$lib/api/settings.js';
	import { uploadFile } from '$lib/api/upload.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';

	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));

	let formName = $state(getServerSettings().server_name || '');
	let formDescription = $state(getServerSettings().server_description || '');
	let formIcon = $state(getServerSettings().server_icon || '');
	let loading = $state(false);
	let error = $state('');
	let success = $state('');
	let uploadingIcon = $state(false);

	async function handleSave() {
		if (!formName.trim()) {
			error = 'Server name is required';
			return;
		}
		loading = true;
		error = '';
		success = '';
		try {
			const updated = await updateServerSettings({
				server_name: formName.trim(),
				server_description: formDescription.trim() || null,
				server_icon: formIcon || null
			});
			setServerSettings(updated);
			success = 'Settings saved';
			setTimeout(() => (success = ''), 3000);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save settings';
		} finally {
			loading = false;
		}
	}

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
			formIcon = result.url;
		} catch (err) {
			error = (err as ApiError).message || 'Failed to upload icon';
		} finally {
			uploadingIcon = false;
			input.value = '';
		}
	}

	function removeIcon() {
		formIcon = '';
	}
</script>

<div class="settings-card">
		<div class="header-row">
			<h1>Server Settings</h1>
		</div>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		{#if success}
			<div class="success-message">{success}</div>
		{/if}

		{#if !canManageServer}
			<p class="muted">You don't have permission to manage server settings.</p>
		{:else}
			<div class="form-section">
				<div class="form-group">
					<label for="serverName">Server Name</label>
					<input id="serverName" type="text" bind:value={formName} maxlength="100" />
				</div>

				<div class="form-group">
					<label for="serverDesc">Description</label>
					<textarea id="serverDesc" bind:value={formDescription} maxlength="500" rows="3"
						placeholder="A short description of your community"></textarea>
					<span class="char-count">{formDescription.length}/500</span>
				</div>

				<div class="form-group">
					<!-- svelte-ignore a11y_label_has_associated_control -->
					<label>Server Icon</label>
					<div class="icon-section">
						{#if formIcon}
							<img src={formIcon} alt="Server icon" class="icon-preview" />
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

				<div class="form-actions">
					<button class="btn-primary" onclick={handleSave} disabled={loading}>
						{loading ? 'Saving...' : 'Save Changes'}
					</button>
				</div>
			</div>
		{/if}
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
		border-radius: 50%;
		object-fit: cover;
		border: 2px solid var(--border);
	}

	.icon-placeholder {
		width: 64px;
		height: 64px;
		border-radius: 50%;
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
</style>
