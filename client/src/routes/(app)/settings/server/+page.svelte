<script lang="ts">
	import { getServerSettings, setServerSettings, hasServerPermission, arePermissionsLoaded } from '$lib/stores/auth.svelte.js';
	import { getServerSettings as fetchServerSettings, updateServerSettings } from '$lib/api/settings.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';
	import ServerIdentityForm from '$lib/components/settings/ServerIdentityForm.svelte';

	const loaded = $derived(arePermissionsLoaded());
	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));

	let formName = $state('');
	let formDescription = $state('');
	let formIcon = $state('');
	let loading = $state(false);
	let error = $state('');
	let success = $state('');
	let settingsLoaded = $state(false);

	function syncForm(s: { server_name?: string | null; server_icon?: string | null; server_description?: string | null }) {
		formName = s.server_name || '';
		formDescription = s.server_description || '';
		formIcon = s.server_icon || '';
	}

	onMount(async () => {
		try {
			const settings = await fetchServerSettings();
			setServerSettings(settings);
			if (!settingsLoaded) {
				syncForm(settings);
				settingsLoaded = true;
			}
		} catch {
			// REST fetch failed — fall through to $effect which waits for WS HELLO
		}
	});

	$effect(() => {
		if (settingsLoaded) return;
		const settings = getServerSettings();
		if (loaded) {
			syncForm(settings);
			settingsLoaded = true;
		}
	});

	async function handleSave() {
		if (!formName.trim()) {
			error = 'Server name is required';
			return;
		}
		loading = true;
		error = '';
		success = '';
		try {
			const updated = await updateServerSettings({
				server_name: formName.trim(),
				server_description: formDescription.trim() || null,
				server_icon: formIcon || null
			});
			setServerSettings(updated);
			success = 'Settings saved';
			setTimeout(() => (success = ''), 3000);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save settings';
		} finally {
			loading = false;
		}
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Server Settings</h1>
	</div>

	{#if error}
		<div class="error-message">{error}</div>
	{/if}

	{#if success}
		<div class="success-message">{success}</div>
	{/if}

	{#if !loaded || !settingsLoaded}
		<p class="muted">Loading…</p>
	{:else if !canManageServer}
		<p class="muted">You don't have permission to manage server settings.</p>
	{:else}
		<ServerIdentityForm
			bind:name={formName}
			bind:description={formDescription}
			bind:icon={formIcon}
			onsave={handleSave}
			saving={loading}
			bind:error
			bind:success
		/>
	{/if}
</div>
