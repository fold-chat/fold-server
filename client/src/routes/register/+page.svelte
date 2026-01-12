<script lang="ts">
	import { register } from '$lib/api/auth.js';
	import { getMe } from '$lib/api/users.js';
	import { setUser } from '$lib/stores/auth.svelte.js';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import type { ApiError } from '$lib/api/client.js';

	let username = $state('');
	let password = $state('');
	let confirmPassword = $state('');
	let inviteCode = $state(page.url.searchParams.get('invite') || '');
	let serverPassword = $state('');
	let error = $state('');
	let loading = $state(false);
	let useInvite = $state(!!page.url.searchParams.get('invite'));

	async function handleSubmit(e: Event) {
		e.preventDefault();
		error = '';

		if (password !== confirmPassword) {
			error = 'Passwords do not match';
			return;
		}

		loading = true;

		try {
			await register({
				username,
				password,
				invite_code: useInvite ? inviteCode : undefined,
				server_password: !useInvite ? serverPassword : undefined
			});
			const user = await getMe();
			setUser(user);
			goto('/');
		} catch (err) {
			const apiErr = err as ApiError;
			error = apiErr.message || apiErr.error || 'Registration failed';
		} finally {
			loading = false;
		}
	}
</script>

<div class="auth-page">
	<div class="auth-card">
		<h1>Create account</h1>
		<p>Join the community</p>

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
				<input id="password" type="password" bind:value={password} autocomplete="new-password" required />
			</div>
			<div class="form-group">
				<label for="confirmPassword">Confirm password</label>
				<input id="confirmPassword" type="password" bind:value={confirmPassword} autocomplete="new-password" required />
			</div>

			<div class="toggle-row">
				<button type="button" class="toggle-btn" class:active={useInvite} onclick={() => (useInvite = true)}>
					Invite code
				</button>
				<button type="button" class="toggle-btn" class:active={!useInvite} onclick={() => (useInvite = false)}>
					Server password
				</button>
			</div>

			{#if useInvite}
				<div class="form-group">
					<label for="inviteCode">Invite code</label>
					<input id="inviteCode" type="text" bind:value={inviteCode} required />
				</div>
			{:else}
				<div class="form-group">
					<label for="serverPassword">Server password</label>
					<input id="serverPassword" type="password" bind:value={serverPassword} required />
				</div>
			{/if}

			<button type="submit" disabled={loading} style="width:100%">
				{loading ? 'Creating account...' : 'Create account'}
			</button>
		</form>

		<div class="link-row">
			Already have an account? <a href="/login">Sign in</a>
		</div>
	</div>
</div>

<style>
	.toggle-row {
		display: flex;
		gap: 0.5rem;
		margin-bottom: 1rem;
	}

	.toggle-btn {
		flex: 1;
		padding: 0.5rem;
		font-size: 0.8rem;
		background: var(--bg-input);
		border: 1px solid var(--border);
		color: var(--text-muted);
	}

	.toggle-btn.active {
		border-color: var(--accent);
		color: var(--text);
	}
</style>
