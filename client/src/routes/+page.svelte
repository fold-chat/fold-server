<script lang="ts">
	import { getUser } from '$lib/stores/auth.svelte.js';
	import { logout } from '$lib/api/auth.js';
	import { reset } from '$lib/stores/auth.svelte.js';
	import { goto } from '$app/navigation';

	async function handleLogout() {
		try {
			await logout();
		} catch {
			// ignore
		}
		reset();
		goto('/login');
	}
</script>

<main>
	<nav>
		<h1>fray</h1>
		<div class="nav-right">
			{#if getUser()}
				<span>{getUser()?.username}</span>
				<a href="/settings/profile">Settings</a>
				<button onclick={handleLogout}>Logout</button>
			{/if}
		</div>
	</nav>
	<div class="content">
		<p>Welcome to Fray</p>
		<p class="muted">Channels and messaging coming soon.</p>
	</div>
</main>

<style>
	main {
		min-height: 100vh;
		display: flex;
		flex-direction: column;
	}

	nav {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 1rem 2rem;
		background: var(--bg-surface);
		border-bottom: 1px solid var(--border);
	}

	nav h1 {
		font-size: 1.25rem;
	}

	.nav-right {
		display: flex;
		align-items: center;
		gap: 1rem;
		font-size: 0.875rem;
	}

	.nav-right button {
		padding: 0.4rem 0.8rem;
		font-size: 0.8rem;
	}

	.content {
		flex: 1;
		display: flex;
		flex-direction: column;
		align-items: center;
		justify-content: center;
		gap: 0.5rem;
	}

	.muted {
		color: var(--text-muted);
	}
</style>
