<script lang="ts">
	import favicon from '$lib/assets/favicon.svg';
	import '../app.css';
	import { init, isInitialized, isSetupRequired, isAuthenticated, getPasswordMustChange, isInsecureMode } from '$lib/stores/auth.svelte.js';
	import { connect, disconnect } from '$lib/stores/ws.svelte.js';
	import { initTheme } from '$lib/stores/theme.svelte.js';
	import { initDensity } from '$lib/stores/density.svelte.js';
	import { initDevices } from '$lib/stores/devices.svelte.js';
	import { goto } from '$app/navigation';
	import { page } from '$app/state';
	import { onMount } from 'svelte';
	import InsecureBanner from '$lib/components/InsecureBanner.svelte';

	let { children } = $props();

	const PUBLIC_ROUTES = ['/login', '/register', '/setup', '/invite', '/maintenance', '/change-password'];

	function isPublicRoute(path: string): boolean {
		return PUBLIC_ROUTES.some((r) => path === r || path.startsWith(r + '/'));
	}

	let tlsBlocked = $state(false);

	function isLocalhost(): boolean {
		const h = window.location.hostname;
		return h === 'localhost' || h === '127.0.0.1' || h === '::1';
	}

	onMount(() => {
		initTheme();
		initDensity();
		initDevices();
		init().then(() => {
			// TLS gate: block HTTP on non-localhost unless insecure mode
			if (window.location.protocol === 'http:' && !isLocalhost() && !isInsecureMode()) {
				tlsBlocked = true;
				return;
			}

			const path = page.url.pathname;
			if (isSetupRequired() && path !== '/setup') {
				goto('/setup');
			} else if (getPasswordMustChange() && path !== '/change-password') {
				goto('/change-password');
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
	<title>Fold</title>
</svelte:head>

{#if tlsBlocked}
	<div class="auth-page">
		<div class="auth-card" style="max-width: 480px">
			<h1>Secure Connection Required</h1>
			<p>Fold requires HTTPS to run securely. You're accessing this server over an unencrypted HTTP connection.</p>
			<p style="margin-top: 0.75rem">To fix this:</p>
			<ul style="margin: 0.5rem 0; padding-left: 1.25rem; font-size: 0.9rem; color: var(--text-muted)">
				<li>Set up a reverse proxy (Caddy, nginx, Traefik) with TLS</li>
				<li>Or set <code>FOLD_INSECURE=true</code> to allow HTTP (not recommended)</li>
			</ul>
		</div>
		<div class="powered-by">Powered by <a href="https://fold.chat" target="_blank" rel="noopener">fold.chat</a></div>
	</div>
{:else if isInitialized()}
	{#if isInsecureMode()}
		<InsecureBanner />
	{/if}
	{@render children()}
{:else}
	<div class="auth-page">
		<p style="color: var(--text-muted)">Loading...</p>
	</div>
{/if}
