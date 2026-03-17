<script lang="ts">
	import { forceChangePassword } from '$lib/api/auth.js';
	import { getMe } from '$lib/api/users.js';
	import { getPasswordMustChange, setPasswordMustChange, setUser } from '$lib/stores/auth.svelte.js';
	import { goto } from '$app/navigation';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';

	let newPassword = $state('');
	let confirmPassword = $state('');
	let error = $state('');
	let loading = $state(false);

	onMount(() => {
		if (!getPasswordMustChange()) {
			goto('/');
		}
	});

	async function handleSubmit(e: Event) {
		e.preventDefault();
		error = '';

		if (newPassword.length < 8) {
			error = 'Password must be at least 8 characters';
			return;
		}
		if (newPassword !== confirmPassword) {
			error = 'Passwords do not match';
			return;
		}

		loading = true;
		try {
			await forceChangePassword(newPassword);
			setPasswordMustChange(false);
			const user = await getMe();
			setUser(user);
			goto('/');
		} catch (err) {
			const apiErr = err as ApiError;
			error = apiErr.message || 'Failed to change password';
		} finally {
			loading = false;
		}
	}
</script>

<div class="auth-page">
	<div class="auth-card">
		<h1>Change Your Password</h1>
		<p>An administrator has reset your password. You must set a new password to continue.</p>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		<form onsubmit={handleSubmit}>
			<div class="form-group">
				<label for="newPassword">New Password</label>
				<input id="newPassword" type="password" bind:value={newPassword} autocomplete="new-password" required />
			</div>
			<div class="form-group">
				<label for="confirmPassword">Confirm Password</label>
				<input id="confirmPassword" type="password" bind:value={confirmPassword} autocomplete="new-password" required />
			</div>
			<button type="submit" disabled={loading} style="width:100%">
				{loading ? 'Changing...' : 'Set New Password'}
			</button>
		</form>
	</div>
</div>
