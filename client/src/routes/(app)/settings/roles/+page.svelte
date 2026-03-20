<script lang="ts">
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import RolesManager from '$lib/components/settings/RolesManager.svelte';

	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Roles</h1>
	</div>

	{#if !canManageRoles}
		<p class="muted">You don't have permission to manage roles.</p>
	{:else}
		<RolesManager />
	{/if}
</div>
