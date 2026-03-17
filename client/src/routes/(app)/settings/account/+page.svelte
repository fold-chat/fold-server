<script lang="ts">
	import { changePassword } from '$lib/api/auth.js';
	import type { ApiError } from '$lib/api/client.js';

	let currentPassword = $state('');
	let newPassword = $state('');
	let confirmPassword = $state('');
	let error = $state('');
	let success = $state('');
	let loading = $state(false);

	async function handleSubmit(e: Event) {
		e.preventDefault();
		error = '';

		if (newPassword.length < 8) {
			error = 'New password must be at least 8 characters';
			return;
		}
		if (newPassword !== confirmPassword) {
			error = 'Passwords do not match';
			return;
		}

		loading = true;
		try {
			await changePassword(currentPassword, newPassword);
			currentPassword = '';
			newPassword = '';
			confirmPassword = '';
			success = 'Password changed successfully';
		} catch (err) {
			const apiErr = err as ApiError;
			error = apiErr.message || 'Failed to change password';
		} finally {
			loading = false;
		}
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Account Settings</h1>
	</div>

	{#if error}
		<div class="error-message">{error}</div>
	{/if}
	{#if success}
		<div class="success-message">{success}</div>
	{/if}

	<div class="form-section">
		<h2 class="section-title">Change Password</h2>
		<p class="section-desc">You will be logged out after changing your password.</p>

		<div class="form-group">
			<label for="currentPassword">Current Password</label>
			<input id="currentPassword" type="password" bind:value={currentPassword} />
		</div>
		<div class="form-group">
			<label for="newPassword">New Password</label>
			<input id="newPassword" type="password" bind:value={newPassword} />
		</div>
		<div class="form-group">
			<label for="confirmPassword">Confirm New Password</label>
			<input id="confirmPassword" type="password" bind:value={confirmPassword} />
		</div>

		<div class="form-actions">
			<button class="btn-primary" type="submit" disabled={loading} onclick={handleSubmit}>
				{loading ? 'Changing...' : 'Change Password'}
			</button>
		</div>
	</div>
</div>

<style>
	.section-title {
		font-size: 0.95rem;
		margin: 0;
	}

	.section-desc {
		font-size: 0.8rem;
		color: var(--text-muted);
		margin: 0;
	}
</style>
