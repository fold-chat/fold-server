<script lang="ts">
	import { setupAccount } from '$lib/api/auth.js';
	import { getMe } from '$lib/api/users.js';
	import { setUser } from '$lib/stores/auth.svelte.js';
	import { goto } from '$app/navigation';
	import type { ApiError } from '$lib/api/client.js';

	let username = $state('');
	let password = $state('');
	let confirmPassword = $state('');
	let error = $state('');
	let loading = $state(false);

	async function handleSubmit(e: Event) {
		e.preventDefault();
		error = '';

		if (password !== confirmPassword) {
			error = 'Passwords do not match';
			return;
		}

		loading = true;

		try {
			await setupAccount(username, password);
			const user = await getMe();
			setUser(user);
			goto('/');
		} catch (err) {
			const apiErr = err as ApiError;
			error = apiErr.message || apiErr.error || 'Setup failed';
		} finally {
			loading = false;
		}
	}
</script>

<div class="auth-page">
	<div class="auth-card">
		<h1>Welcome to Fold</h1>
		<p>Create your admin account to get started.</p>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		<form onsubmit={handleSubmit}>
			<div class="form-group">
				<label for="username">Admin username</label>
				<input id="username" type="text" bind:value={username} autocomplete="username" required />
			</div>
			<div class="form-group">
				<label for="password">Password</label>
				<input id="password" type="password" bind:value={password} autocomplete="new-password" required />
			</div>
			<div class="form-group">
				<label for="confirmPassword">Confirm password</label>
				<input id="confirmPassword" type="password" bind:value={confirmPassword} autocomplete="new-password" required />
			</div>
			<button type="submit" disabled={loading} style="width:100%">
				{loading ? 'Creating...' : 'Create admin account'}
			</button>
		</form>
	</div>
<div class="powered-by">Powered by <a href="https://fold.chat" target="_blank" rel="noopener">fold.chat</a></div>
</div>
