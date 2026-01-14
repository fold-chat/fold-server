<script lang="ts">
	import favicon from '$lib/assets/favicon.svg';
	import '../app.css';
	import { init, isInitialized, isSetupRequired, isAuthenticated } from '$lib/stores/auth.svelte.js';
	import { connect, disconnect } from '$lib/stores/ws.svelte.js';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';

	let { children } = $props();

	const PUBLIC_ROUTES = ['/login', '/register', '/setup', '/invite'];

	function isPublicRoute(path: string): boolean {
		return PUBLIC_ROUTES.some((r) => path === r || path.startsWith(r + '/'));
	}

	onMount(() => {
		init();

		const path = page.url.pathname;

		if (isSetupRequired() && path !== '/setup') {
			goto('/setup');
			return;
		}

		if (!isSetupRequired() && !isAuthenticated() && !isPublicRoute(path)) {
			goto('/login');
		} else if (isAuthenticated()) {
			connect();
		}

		return () => disconnect();
	});
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>Fray</title>
</svelte:head>

{#if isInitialized()}
	{@render children()}
{:else}
	<div class="auth-page">
		<p style="color: var(--text-muted)">Loading...</p>
	</div>
{/if}
