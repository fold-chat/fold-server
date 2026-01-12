<script lang="ts">
	import { login } from '$lib/api/auth.js';
	import { getMe } from '$lib/api/users.js';
	import { setUser } from '$lib/stores/auth.svelte.js';
	import { goto } from '$app/navigation';
	import type { ApiError } from '$lib/api/client.js';

	let username = $state('');
	let password = $state('');
	let error = $state('');
	let loading = $state(false);

	async function handleSubmit(e: Event) {
		e.preventDefault();
		error = '';
		loading = true;

		try {
			await login(username, password);
			const user = await getMe();
			setUser(user);
			goto('/');
		} catch (err) {
			const apiErr = err as ApiError;
			if (apiErr.error === 'account_locked') {
				error = `Account locked. Try again in ${apiErr.retry_after} seconds.`;
			} else {
				error = apiErr.message || 'Invalid username or password';
			}
		} finally {
			loading = false;
		}
	}
</script>

<div class="auth-page">
	<div class="auth-card">
		<h1>Sign in</h1>
		<p>Welcome back to Fray</p>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		<form onsubmit={handleSubmit}>
			<div class="form-group">
				<label for="username">Username</label>
				<input id="username" type="text" bind:value={username} autocomplete="username" required />
			</div>
			<div class="form-group">
				<label for="password">Password</label>
				<input id="password" type="password" bind:value={password} autocomplete="current-password" required />
			</div>
			<button type="submit" disabled={loading} style="width:100%">
				{loading ? 'Signing in...' : 'Sign in'}
			</button>
		</form>

		<div class="link-row">
			Don't have an account? <a href="/register">Register</a>
		</div>
	</div>
</div>
