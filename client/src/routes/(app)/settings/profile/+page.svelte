<script lang="ts">
	import { getUser, setUser } from '$lib/stores/auth.svelte.js';
	import { updateMe } from '$lib/api/users.js';
	import { uploadFile } from '$lib/api/upload.js';
	import type { ApiError } from '$lib/api/client.js';
	import AvatarCropper from '$lib/components/AvatarCropper.svelte';

	let displayName = $state(getUser()?.display_name || '');
	let bio = $state(getUser()?.bio || '');
	let statusText = $state(getUser()?.status_text || '');
	let error = $state('');
	let success = $state('');
	let loading = $state(false);
	let uploading = $state(false);
	let cropFile = $state<File | null>(null);

	async function handleSave(e: Event) {
		e.preventDefault();
		error = '';
		success = '';
		loading = true;

		try {
			const updated = await updateMe({
				display_name: displayName,
				bio: bio,
				status_text: statusText
			});
			setUser(updated);
			success = 'Profile updated';
		} catch (err) {
			const apiErr = err as ApiError;
			error = apiErr.message || 'Failed to update profile';
		} finally {
			loading = false;
		}
	}

	function handleAvatarSelect(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		cropFile = file;
		// Reset input so the same file can be re-selected
		input.value = '';
	}

	async function handleCroppedUpload(croppedFile: File) {
		cropFile = null;
		uploading = true;
		error = '';

		try {
			const result = await uploadFile(croppedFile);
			const updated = await updateMe({ avatar_url: result.url });
			setUser(updated);
			success = 'Avatar updated';
		} catch (err) {
			const apiErr = err as ApiError;
			error = apiErr.message || 'Failed to upload avatar';
		} finally {
			uploading = false;
		}
	}

	function handleCropCancel() {
		cropFile = null;
	}
</script>

<div class="settings-card">
		<div class="header-row">
			<h1>Profile Settings</h1>
		</div>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}
		{#if success}
			<div class="success-message">{success}</div>
		{/if}

		<div class="form-section">
			<div class="form-group">
				<!-- svelte-ignore a11y_label_has_associated_control -->
				<label>Avatar</label>
				<div class="avatar-section">
					{#if getUser()?.avatar_url}
						<img src={getUser()?.avatar_url} alt="Avatar" class="avatar" />
					{:else}
						<div class="avatar placeholder">{getUser()?.username?.charAt(0).toUpperCase()}</div>
					{/if}
					<label class="upload-btn">
						{uploading ? 'Uploading...' : 'Change avatar'}
						<input type="file" accept="image/*" onchange={handleAvatarSelect} hidden />
					</label>
				</div>
			</div>

			<div class="form-group">
				<label for="displayName">Display Name</label>
				<input id="displayName" type="text" bind:value={displayName} />
			</div>
			<div class="form-group">
				<label for="bio">Bio</label>
				<textarea id="bio" bind:value={bio} rows="3"></textarea>
			</div>
			<div class="form-group">
				<label for="statusText">Status</label>
				<input id="statusText" type="text" bind:value={statusText} placeholder="What are you up to?" />
			</div>

			<div class="form-actions">
				<button class="btn-primary" type="submit" disabled={loading} onclick={handleSave}>
					{loading ? 'Saving...' : 'Save Changes'}
				</button>
			</div>
		</div>
</div>

{#if cropFile}
	<AvatarCropper imageFile={cropFile} onCrop={handleCroppedUpload} onCancel={handleCropCancel} />
{/if}

<style>
	.avatar-section {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.avatar {
		width: 64px;
		height: 64px;
		border-radius: 50%;
		object-fit: cover;
		border: 2px solid var(--border);
	}

	.avatar.placeholder {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--accent);
		color: white;
		font-size: 1.5rem;
		font-weight: bold;
	}

	.upload-btn {
		cursor: pointer;
		color: var(--accent);
		font-size: 0.875rem;
	}

	.upload-btn:hover {
		text-decoration: underline;
	}
</style>
