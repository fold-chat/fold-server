<script lang="ts">
	import { getUser, setUser } from '$lib/stores/auth.svelte.js';
	import { updateMe } from '$lib/api/users.js';
	import { uploadFile } from '$lib/api/upload.js';
	import type { ApiError } from '$lib/api/client.js';

	let displayName = $state(getUser()?.display_name || '');
	let bio = $state(getUser()?.bio || '');
	let statusText = $state(getUser()?.status_text || '');
	let error = $state('');
	let success = $state('');
	let loading = $state(false);
	let uploading = $state(false);

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

	async function handleAvatarUpload(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;

		uploading = true;
		error = '';

		try {
			const result = await uploadFile(file);
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
</script>

<div class="auth-page">
	<div class="auth-card">
		<h1>Profile settings</h1>
		<p><a href="/">&larr; Back</a></p>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}
		{#if success}
			<div class="success-message">{success}</div>
		{/if}

		<div class="avatar-section">
			{#if getUser()?.avatar_url}
				<img src={getUser()?.avatar_url} alt="Avatar" class="avatar" />
			{:else}
				<div class="avatar placeholder">{getUser()?.username?.charAt(0).toUpperCase()}</div>
			{/if}
			<label class="upload-btn">
				{uploading ? 'Uploading...' : 'Change avatar'}
				<input type="file" accept="image/*" onchange={handleAvatarUpload} hidden />
			</label>
		</div>

		<form onsubmit={handleSave}>
			<div class="form-group">
				<label for="displayName">Display name</label>
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
			<button type="submit" disabled={loading} style="width:100%">
				{loading ? 'Saving...' : 'Save changes'}
			</button>
		</form>
	</div>
</div>

<style>
	.avatar-section {
		display: flex;
		align-items: center;
		gap: 1rem;
		margin-bottom: 1.5rem;
	}

	.avatar {
		width: 64px;
		height: 64px;
		border-radius: 50%;
		object-fit: cover;
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

	.success-message {
		color: var(--success);
		font-size: 0.875rem;
		margin-bottom: 1rem;
	}
</style>
