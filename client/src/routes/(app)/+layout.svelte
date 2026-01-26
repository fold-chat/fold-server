<script lang="ts">
	import { logout } from '$lib/api/auth.js';
	import { reset, getServerName } from '$lib/stores/auth.svelte.js';
	import { disconnect } from '$lib/stores/ws.svelte.js';
	import { toggleSearch } from '$lib/stores/search.svelte.js';
	import { getTotalMentionCount } from '$lib/stores/channels.svelte.js';
	import { goto } from '$app/navigation';
	import Sidebar from '$lib/components/Sidebar.svelte';
	import SearchModal from '$lib/components/SearchModal.svelte';

	let { children } = $props();

	$effect(() => {
		const count = getTotalMentionCount();
		const name = getServerName();
		document.title = count > 0 ? `(${count}) ${name}` : name;
	});

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

	function onKeydown(e: KeyboardEvent) {
		if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
			e.preventDefault();
			toggleSearch();
		}
	}
</script>

<svelte:window onkeydown={onKeydown} />

<div class="app-shell">
	<Sidebar />
	<main class="main-content">
		{@render children()}
	</main>
</div>

<SearchModal />

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
