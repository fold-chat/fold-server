<script lang="ts">
	import favicon from '$lib/assets/favicon.svg';
	import '../app.css';
import { init, isInitialized, isSetupRequired, isAuthenticated } from '$lib/stores/auth.svelte.js';
	import { connect, disconnect } from '$lib/stores/ws.svelte.js';
	import { initTheme } from '$lib/stores/theme.svelte.js';
	import { initDensity } from '$lib/stores/density.svelte.js';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';

	let { children } = $props();

	const PUBLIC_ROUTES = ['/login', '/register', '/setup', '/invite'];

	function isPublicRoute(path: string): boolean {
		return PUBLIC_ROUTES.some((r) => path === r || path.startsWith(r + '/'));
	}

	onMount(() => {
		initTheme();
		initDensity();
		init().then(() => {
			const path = page.url.pathname;
			if (isSetupRequired() && path !== '/setup') {
				goto('/setup');
			} else if (!isSetupRequired() && !isAuthenticated() && !isPublicRoute(path)) {
				goto('/login');
			}
		});
		return () => disconnect();
	});

	$effect(() => {
		if (isInitialized() && isAuthenticated()) {
			connect();
		}
	});
</script>

<svelte:head>
	<link rel="icon" href={favicon} />
	<title>Kith</title>
</svelte:head>

{#if isInitialized()}
	{@render children()}
{:else}
	<div class="auth-page">
		<p style="color: var(--text-muted)">Loading...</p>
	</div>
{/if}
