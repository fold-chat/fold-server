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

<div class="settings-page">
	<div class="settings-card">
		<div class="header-row">
			<h1>Server Settings</h1>
			<p><a href="/">&larr; Back</a></p>
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
</div>

<style>
	.settings-page {
		padding: 2rem;
		max-width: 800px;
		margin: 0 auto;
		overflow-y: auto;
		height: 100vh;
	}

	.settings-card {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 1.5rem;
	}

	.header-row {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		margin-bottom: 1rem;
	}

	h1 {
		font-size: 1.25rem;
		margin: 0;
	}

	.muted {
		color: var(--text-muted);
		font-size: 0.875rem;
	}

	.form-section {
		display: flex;
		flex-direction: column;
		gap: 1.25rem;
	}

	.form-group {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.form-group label {
		font-size: 0.75rem;
		font-weight: 600;
		color: var(--text-muted);
		text-transform: uppercase;
	}

	.form-group input[type='text'],
	.form-group textarea {
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.5rem 0.6rem;
		font-size: 0.875rem;
		font-family: inherit;
		resize: vertical;
	}

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

	.form-actions {
		display: flex;
		gap: 0.5rem;
		margin-top: 0.5rem;
	}

	.btn-primary {
		padding: 0.5rem 1rem;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 4px;
		font-size: 0.875rem;
		cursor: pointer;
	}

	.btn-primary:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.btn-sm {
		padding: 0.25rem 0.5rem;
		font-size: 0.75rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 3px;
		cursor: pointer;
	}

	.btn-sm:hover {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.btn-danger {
		border-color: #e74c3c;
		color: #e74c3c;
	}

	.btn-danger:hover {
		background: rgba(231, 76, 60, 0.1);
	}

	.error-message {
		color: #e74c3c;
		font-size: 0.85rem;
		margin-bottom: 0.75rem;
		padding: 0.5rem;
		background: rgba(231, 76, 60, 0.1);
		border-radius: 4px;
	}

	.success-message {
		color: #2ecc71;
		font-size: 0.85rem;
		margin-bottom: 0.75rem;
		padding: 0.5rem;
		background: rgba(46, 204, 113, 0.1);
		border-radius: 4px;
	}
</style>
