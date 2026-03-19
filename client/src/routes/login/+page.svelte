<script lang="ts">
	import { login } from '$lib/api/auth.js';
	import { getMe } from '$lib/api/users.js';
	import { setUser, setPasswordMustChange } from '$lib/stores/auth.svelte.js';
	import { goto } from '$app/navigation';
	import type { ApiError } from '$lib/api/client.js';

	let username = $state('');
	let password = $state('');
	let loading = $state(false);

	const reasonMessages: Record<string, string> = {
		banned: 'You have been banned from this server.'
	};

	const urlReason = typeof window !== 'undefined' ? new URLSearchParams(window.location.search).get('reason') : null;
	let error = $state(urlReason ? reasonMessages[urlReason] || '' : '');

	function formatDuration(seconds: number): string {
		const h = Math.floor(seconds / 3600);
		const m = Math.floor((seconds % 3600) / 60);
		if (h > 0 && m > 0) return `${h}h ${m}m`;
		if (h > 0) return `${h}h`;
		if (m > 0) return `${m}m`;
		return `${seconds}s`;
	}

	async function handleSubmit(e: Event) {
		e.preventDefault();
		error = '';
		loading = true;

		try {
			const result = await login(username, password);
			if (result.password_must_change) {
				setPasswordMustChange(true);
				goto('/change-password');
				return;
			}
			const user = await getMe();
			setUser(user);
			goto('/');
		} catch (err) {
			if (err instanceof TypeError) {
				error = 'Unable to reach server. Please try again later.';
			} else {
				const apiErr = err as ApiError;
				if (apiErr.error === 'maintenance') {
					// Redirect handled by api client — don't flash an error
					return;
				} else if (apiErr.error === 'banned') {
					error = 'You have been banned from this server.';
				} else if (apiErr.error === 'account_locked') {
					error = `Account locked. Try again in ${formatDuration(apiErr.retry_after ?? 0)}.`;
				} else if (apiErr.status && apiErr.status >= 500) {
					error = 'Server error. Please try again later.';
				} else {
					error = apiErr.message || 'Invalid username or password.';
				}
			}
		} finally {
			loading = false;
		}
	}
</script>

<div class="auth-page">
	<div class="auth-card">
		<h1>Sign in</h1>
		<p>Welcome back to Fold</p>

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
<div class="powered-by">Powered by <a href="https://fold.chat" target="_blank" rel="noopener">fold.chat</a></div>
</div>
