<script lang="ts">
	import { logout } from '$lib/api/auth.js';
	import { reset } from '$lib/stores/auth.svelte.js';
	import { disconnect } from '$lib/stores/ws.svelte.js';
	import { goto } from '$app/navigation';
	import { createChannel, createCategory } from '$lib/api/channels.js';
	import Sidebar from '$lib/components/Sidebar.svelte';

	let { children } = $props();

	async function handleLogout() {
		disconnect();
		try {
			await logout();
		} catch {
			// ignore
		}
		reset();
		goto('/login');
	}

	async function handleCreateChannel() {
		const name = prompt('Channel name:');
		if (name) {
			try {
				await createChannel({ name });
			} catch {
				// handle error
			}
		}
	}

	async function handleCreateCategory() {
		const name = prompt('Category name:');
		if (name) {
			try {
				await createCategory({ name });
			} catch {
				// handle error
			}
		}
	}
</script>

<div class="app-shell">
	<Sidebar onCreateChannel={handleCreateChannel} onCreateCategory={handleCreateCategory} />
	<main class="main-content">
		{@render children()}
	</main>
</div>

<style>
	.app-shell {
		display: flex;
		height: 100vh;
		overflow: hidden;
	}

	.main-content {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
</style>
