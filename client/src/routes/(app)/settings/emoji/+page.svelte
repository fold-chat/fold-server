<script lang="ts">
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { getCustomEmoji, addCustomEmoji, removeCustomEmoji as removeEmojiFromStore } from '$lib/stores/emoji.svelte.js';
	import { uploadEmoji, deleteEmoji } from '$lib/api/emoji.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';

	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));
	let emojiList = $derived(getCustomEmoji());

	let newName = $state('');
	let fileInput = $state<HTMLInputElement | null>(null);
	let selectedFile = $state<File | null>(null);
	let preview = $state<string | null>(null);
	let uploading = $state(false);
	let error = $state('');
	let success = $state('');
	let deletingId = $state<string | null>(null);

	function handleFileSelect(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;

		if (!['image/png', 'image/gif', 'image/webp'].includes(file.type)) {
			error = 'Only PNG, GIF, and WebP images are allowed';
			return;
		}
		if (file.size > 256 * 1024) {
			error = 'Emoji images must be under 256KB';
			return;
		}

		selectedFile = file;
		preview = URL.createObjectURL(file);
		error = '';
	}

	function clearFile() {
		if (preview) URL.revokeObjectURL(preview);
		selectedFile = null;
		preview = null;
		if (fileInput) fileInput.value = '';
	}

	async function handleUpload() {
		if (!selectedFile || !newName.trim()) {
			error = 'Name and image are required';
			return;
		}

		const name = newName.trim().toLowerCase();
		if (!/^[a-zA-Z0-9_]{2,32}$/.test(name)) {
			error = 'Name must be 2-32 characters (letters, numbers, underscores)';
			return;
		}

		uploading = true;
		error = '';
		success = '';
		try {
			const emoji = await uploadEmoji(selectedFile, name);
			addCustomEmoji(emoji);
			newName = '';
			clearFile();
			success = `Emoji :${name}: added!`;
			setTimeout(() => (success = ''), 3000);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to upload emoji';
		} finally {
			uploading = false;
		}
	}

	async function handleDelete(id: string, name: string) {
		if (!confirm(`Delete :${name}:? This cannot be undone.`)) return;
		deletingId = id;
		error = '';
		try {
			await deleteEmoji(id);
			removeEmojiFromStore(id);
			success = `Emoji :${name}: deleted`;
			setTimeout(() => (success = ''), 3000);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to delete emoji';
		} finally {
			deletingId = null;
		}
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Custom Emoji</h1>
	</div>

	{#if error}
		<div class="error-message">{error}</div>
	{/if}

	{#if success}
		<div class="success-message">{success}</div>
	{/if}

	{#if !canManageServer}
		<p class="muted">You don't have permission to manage emoji.</p>

		{#if emojiList.length > 0}
			<div class="emoji-list">
				{#each emojiList as emoji}
					<div class="emoji-item">
						<img src={emoji.url} alt={emoji.name} class="emoji-preview" />
						<span class="emoji-name">:{emoji.name}:</span>
					</div>
				{/each}
			</div>
		{/if}
	{:else}
		<div class="form-section">
			<div class="upload-section">
				<div class="form-group">
					<label for="emojiName">Emoji Name</label>
					<input id="emojiName" type="text" bind:value={newName} maxlength="32"
						placeholder="e.g. party_parrot" pattern="[a-zA-Z0-9_]+" />
					<span class="hint">2-32 characters, letters, numbers, underscores</span>
				</div>

				<div class="form-group">
					<!-- svelte-ignore a11y_label_has_associated_control -->
					<label>Image</label>
					<div class="file-row">
						{#if preview}
							<img src={preview} alt="Preview" class="file-preview" />
							<button class="btn-sm" onclick={clearFile}>Remove</button>
						{/if}
						<label class="btn-sm upload-btn">
							{selectedFile ? 'Change File' : 'Choose File'}
							<input type="file" accept="image/png,image/gif,image/webp" bind:this={fileInput}
								onchange={handleFileSelect} hidden />
						</label>
						<span class="hint">PNG, GIF, or WebP. Max 256KB.</span>
					</div>
				</div>

				<div class="form-actions">
					<button class="btn-primary" onclick={handleUpload}
						disabled={uploading || !selectedFile || !newName.trim()}>
						{uploading ? 'Uploading...' : 'Add Emoji'}
					</button>
				</div>
			</div>
		</div>

		{#if emojiList.length > 0}
			<div class="emoji-list">
				<h2>Current Emoji ({emojiList.length})</h2>
				{#each emojiList as emoji}
					<div class="emoji-item">
						<img src={emoji.url} alt={emoji.name} class="emoji-preview" />
						<span class="emoji-name">:{emoji.name}:</span>
						<button class="btn-sm btn-danger" onclick={() => handleDelete(emoji.id, emoji.name)}
							disabled={deletingId === emoji.id}>
							{deletingId === emoji.id ? 'Deleting...' : 'Delete'}
						</button>
					</div>
				{/each}
			</div>
		{:else}
			<p class="muted" style="margin-top: 1rem;">No custom emoji yet. Upload one above!</p>
		{/if}
	{/if}
</div>

<style>
	.hint {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.upload-section {
		padding-bottom: 1rem;
		border-bottom: 1px solid var(--border);
	}

	.file-row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.file-preview {
		width: 32px;
		height: 32px;
		object-fit: contain;
		border-radius: 4px;
		border: 1px solid var(--border);
	}

	.upload-btn {
		cursor: pointer;
	}

	.emoji-list {
		margin-top: 1.25rem;
	}

	.emoji-list h2 {
		font-size: 0.85rem;
		font-weight: 600;
		color: var(--text-muted);
		text-transform: uppercase;
		margin: 0 0 0.75rem;
	}

	.emoji-item {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.4rem 0;
		border-bottom: 1px solid var(--border);
	}

	.emoji-item:last-child {
		border-bottom: none;
	}

	.emoji-preview {
		width: 32px;
		height: 32px;
		object-fit: contain;
	}

	.emoji-name {
		flex: 1;
		font-size: 0.85rem;
		font-family: monospace;
		color: var(--text);
	}
</style>
