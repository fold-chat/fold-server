<script lang="ts">
	import { getRolesList } from '$lib/stores/roles.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { createRole, updateRole, deleteRole } from '$lib/api/roles.js';
	import { PermissionName, PERMISSION_GROUPS } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';

	let editingRole = $state<string | null>(null);
	let formName = $state('');
	let formColor = $state('#5865f2');
	let formPosition = $state(10);
	let formPermissions = $state<Set<string>>(new Set());
	let creating = $state(false);
	let error = $state('');
	let loading = $state(false);
	let deleteConfirm = $state<string | null>(null);

	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));

	function startCreate() {
		editingRole = null;
		formName = '';
		formColor = '#5865f2';
		formPosition = Math.max(...getRolesList().map((r) => r.position), 0) + 1;
		formPermissions = new Set();
		creating = true;
		error = '';
	}

	function startEdit(roleId: string) {
		const role = getRolesList().find((r) => r.id === roleId);
		if (!role || role.id === 'owner') return;
		editingRole = roleId;
		formName = role.name;
		formColor = role.color || '#5865f2';
		formPosition = role.position;
		formPermissions = new Set(role.permissions);
		creating = false;
		error = '';
	}

	function cancelEdit() {
		editingRole = null;
		creating = false;
		error = '';
	}

	function togglePerm(perm: string) {
		formPermissions = new Set(formPermissions);
		if (formPermissions.has(perm)) formPermissions.delete(perm);
		else formPermissions.add(perm);
	}

	async function handleSave() {
		if (!formName.trim()) {
			error = 'Name required';
			return;
		}
		loading = true;
		error = '';
		try {
			if (creating) {
				await createRole({
					name: formName.trim(),
					permissions: [...formPermissions],
					position: formPosition,
					color: formColor
				});
			} else if (editingRole) {
				await updateRole(editingRole, {
					name: formName.trim(),
					permissions: [...formPermissions],
					position: formPosition,
					color: formColor
				});
			}
			cancelEdit();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save role';
		} finally {
			loading = false;
		}
	}

	async function handleDelete(roleId: string) {
		loading = true;
		error = '';
		try {
			await deleteRole(roleId);
			deleteConfirm = null;
			if (editingRole === roleId) cancelEdit();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to delete role';
		} finally {
			loading = false;
		}
	}
</script>

<div class="settings-page">
	<div class="settings-card">
		<div class="header-row">
			<h1>Roles</h1>
			<p><a href="/">&larr; Back</a></p>
		</div>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		{#if !canManageRoles}
			<p class="muted">You don't have permission to manage roles.</p>
		{/if}

		<!-- Role list -->
		<div class="role-list">
			{#each getRolesList() as role}
				<div class="role-item" class:active={editingRole === role.id}>
					<span class="role-badge" style="background-color: {role.color || '#99aab5'}">&nbsp;</span>
					<span class="role-name">{role.name}</span>
					{#if role.id === 'owner'}
						<span class="protected-badge">Owner</span>
					{:else if role.is_default}
						<span class="default-badge">Default</span>
					{/if}
					<span class="role-position">#{role.position}</span>
					{#if canManageRoles && role.id !== 'owner'}
						<button class="btn-sm" onclick={() => startEdit(role.id)}>Edit</button>
						<button class="btn-sm btn-danger" onclick={() => (deleteConfirm = role.id)}>Delete</button>
					{/if}
				</div>

				{#if deleteConfirm === role.id}
					<div class="confirm-bar">
						<span>Delete "{role.name}"? This cannot be undone.</span>
						<button class="btn-sm btn-danger" onclick={() => handleDelete(role.id)} disabled={loading}>
							Confirm
						</button>
						<button class="btn-sm" onclick={() => (deleteConfirm = null)}>Cancel</button>
					</div>
				{/if}
			{/each}
		</div>

		{#if canManageRoles && !creating && !editingRole}
			<button class="btn-primary" onclick={startCreate}>+ Create Role</button>
		{/if}

		<!-- Create / Edit form -->
		{#if creating || editingRole}
			<div class="role-form">
				<h2>{creating ? 'Create Role' : 'Edit Role'}</h2>
				<div class="form-row">
					<div class="form-group">
						<label for="roleName">Name</label>
						<input id="roleName" type="text" bind:value={formName} />
					</div>
					<div class="form-group">
						<label for="roleColor">Color</label>
						<input id="roleColor" type="color" bind:value={formColor} />
					</div>
					<div class="form-group">
						<label for="rolePosition">Position</label>
						<input id="rolePosition" type="number" bind:value={formPosition} min="1" />
					</div>
				</div>

				<h3>Permissions</h3>
				{#each Object.entries(PERMISSION_GROUPS) as [group, perms]}
					<div class="perm-group">
						<h4>{group}</h4>
						{#each perms as perm}
							<label class="perm-toggle">
								<input
									type="checkbox"
									checked={formPermissions.has(perm.name)}
									onchange={() => togglePerm(perm.name)}
								/>
								<span class="perm-label">{perm.label}</span>
								<span class="perm-desc">{perm.desc}</span>
							</label>
						{/each}
					</div>
				{/each}

				<div class="form-actions">
					<button class="btn-primary" onclick={handleSave} disabled={loading}>
						{loading ? 'Saving...' : 'Save'}
					</button>
					<button class="btn-sm" onclick={cancelEdit}>Cancel</button>
				</div>
			</div>
		{/if}
	</div>
</div>

<style>
	.settings-page {
		padding: 2rem;
		max-width: 800px;
		margin: 0 auto;
		overflow-y: auto;
		height: 100vh;
	}

	.settings-card {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 1.5rem;
	}

	.header-row {
		display: flex;
		justify-content: space-between;
		align-items: baseline;
		margin-bottom: 1rem;
	}

	h1 {
		font-size: 1.25rem;
		margin: 0;
	}

	.muted {
		color: var(--text-muted);
		font-size: 0.875rem;
	}

	.role-list {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		margin-bottom: 1rem;
	}

	.role-item {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		border-radius: 4px;
		font-size: 0.875rem;
	}

	.role-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.03));
	}

	.role-item.active {
		background: var(--bg-active, rgba(255, 255, 255, 0.06));
	}

	.role-badge {
		width: 12px;
		height: 12px;
		border-radius: 50%;
		flex-shrink: 0;
	}

	.role-name {
		flex: 1;
		font-weight: 500;
	}

	.protected-badge {
		font-size: 0.65rem;
		padding: 0.1rem 0.4rem;
		background: #e74c3c;
		color: white;
		border-radius: 3px;
		font-weight: 600;
	}

	.default-badge {
		font-size: 0.65rem;
		padding: 0.1rem 0.4rem;
		background: var(--accent, #5865f2);
		color: white;
		border-radius: 3px;
		font-weight: 600;
	}

	.role-position {
		color: var(--text-muted);
		font-size: 0.75rem;
	}

	.role-form {
		border-top: 1px solid var(--border);
		margin-top: 1rem;
		padding-top: 1rem;
	}

	.form-row {
		display: flex;
		gap: 1rem;
		margin-bottom: 1rem;
	}

	.form-group {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.form-group label {
		font-size: 0.75rem;
		font-weight: 600;
		color: var(--text-muted);
		text-transform: uppercase;
	}

	.form-group input[type='text'],
	.form-group input[type='number'] {
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.4rem 0.6rem;
		font-size: 0.875rem;
	}

	.form-group input[type='color'] {
		width: 40px;
		height: 32px;
		border: 1px solid var(--border);
		border-radius: 4px;
		cursor: pointer;
		background: none;
	}

	h2 {
		font-size: 1rem;
		margin: 0 0 0.75rem;
	}

	h3 {
		font-size: 0.9rem;
		margin: 0.75rem 0 0.5rem;
	}

	.perm-group {
		margin-bottom: 0.75rem;
	}

	.perm-group h4 {
		font-size: 0.75rem;
		text-transform: uppercase;
		color: var(--text-muted);
		margin: 0 0 0.35rem;
	}

	.perm-toggle {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.25rem 0;
		font-size: 0.85rem;
		cursor: pointer;
	}

	.perm-label {
		font-weight: 500;
		min-width: 140px;
	}

	.perm-desc {
		color: var(--text-muted);
		font-size: 0.75rem;
	}

	.form-actions {
		display: flex;
		gap: 0.5rem;
		margin-top: 1rem;
	}

	.confirm-bar {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		background: rgba(231, 76, 60, 0.1);
		border-radius: 4px;
		font-size: 0.8rem;
	}

	.btn-primary {
		padding: 0.5rem 1rem;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 4px;
		font-size: 0.875rem;
		cursor: pointer;
	}

	.btn-primary:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.btn-sm {
		padding: 0.25rem 0.5rem;
		font-size: 0.75rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 3px;
		cursor: pointer;
	}

	.btn-sm:hover {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.btn-danger {
		border-color: #e74c3c;
		color: #e74c3c;
	}

	.btn-danger:hover {
		background: rgba(231, 76, 60, 0.1);
	}

	.error-message {
		color: #e74c3c;
		font-size: 0.85rem;
		margin-bottom: 0.75rem;
		padding: 0.5rem;
		background: rgba(231, 76, 60, 0.1);
		border-radius: 4px;
	}
</style>
