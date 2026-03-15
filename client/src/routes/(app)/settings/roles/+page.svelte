<script lang="ts">
	import { getRolesList } from '$lib/stores/roles.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { createRole, updateRole, deleteRole, setDefaultRole, reorderRoles } from '$lib/api/roles.js';
	import { PermissionName, PERMISSION_GROUPS, getAllPrerequisites, getAllDependents, getForcedPrereqs } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';

	let editingRole = $state<string | null>(null);
	let formName = $state('');
	let formColor = $state('#5865f2');
	let formPermissions = $state<Set<string>>(new Set());
	let creating = $state(false);
	let error = $state('');
	let loading = $state(false);
	let deleteConfirm = $state<string | null>(null);
	let dragRoleId = $state<string | null>(null);
	let dragOverId = $state<string | null>(null);

	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));
	const isAdmin = $derived(formPermissions.has(PermissionName.ADMINISTRATOR));
	const forcedPerms = $derived(getForcedPrereqs(formPermissions));

	function isPermChecked(perm: string): boolean {
		if (isAdmin && perm !== PermissionName.ADMINISTRATOR) return true;
		return formPermissions.has(perm);
	}

	function isPermDisabled(perm: string): boolean {
		if (isAdmin && perm !== PermissionName.ADMINISTRATOR) return true;
		return forcedPerms.has(perm);
	}

	function startCreate() {
		editingRole = null;
		formName = '';
		formColor = '#5865f2';
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
		if (isPermDisabled(perm)) return;
		formPermissions = new Set(formPermissions);
		if (formPermissions.has(perm)) {
			// Remove perm + all dependents
			formPermissions.delete(perm);
			for (const dep of getAllDependents(perm)) {
				formPermissions.delete(dep);
			}
		} else {
			// Add perm + all prerequisites
			formPermissions.add(perm);
			for (const prereq of getAllPrerequisites(perm)) {
				formPermissions.add(prereq);
			}
		}
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
					color: formColor
				});
			} else if (editingRole) {
				await updateRole(editingRole, {
					name: formName.trim(),
					permissions: [...formPermissions],
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

	async function handleSetDefault(roleId: string) {
		loading = true;
		error = '';
		try {
			await setDefaultRole(roleId);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to set default role';
		} finally {
			loading = false;
		}
	}

	function onDragStart(e: DragEvent, roleId: string) {
		dragRoleId = roleId;
		if (e.dataTransfer) {
			e.dataTransfer.effectAllowed = 'move';
		}
	}

	function onDragOver(e: DragEvent, roleId: string) {
		e.preventDefault();
		if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';
		dragOverId = roleId;
	}

	function onDragLeave() {
		dragOverId = null;
	}

	function onDragEnd() {
		dragRoleId = null;
		dragOverId = null;
	}

	async function onDrop(e: DragEvent, targetId: string) {
		e.preventDefault();
		dragOverId = null;
		if (!dragRoleId || dragRoleId === targetId) {
			dragRoleId = null;
			return;
		}

		// Reorder: move dragged role to target's position
		const list = getRolesList().map((r) => r.id);
		const fromIdx = list.indexOf(dragRoleId);
		const toIdx = list.indexOf(targetId);
		if (fromIdx === -1 || toIdx === -1) return;

		list.splice(fromIdx, 1);
		list.splice(toIdx, 0, dragRoleId);
		dragRoleId = null;

		// Assign sequential positions (1-based)
		const items = list.map((id, i) => ({ id, position: i + 1 }));
		loading = true;
		error = '';
		try {
			await reorderRoles(items);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to reorder roles';
		} finally {
			loading = false;
		}
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Roles</h1>
	</div>
	<p class="default-hint">The default role is automatically assigned to new members on join.</p>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		{#if !canManageRoles}
			<p class="muted">You don't have permission to manage roles.</p>
		{/if}

		<!-- Role list -->
		<div class="role-list">
			{#each getRolesList() as role}
				<!-- svelte-ignore a11y_no_static_element_interactions -->
				<div
					class="role-item"
					class:active={editingRole === role.id}
					class:drag-over={dragOverId === role.id && dragRoleId !== role.id}
					class:dragging={dragRoleId === role.id}
					draggable={canManageRoles}
					ondragstart={(e: DragEvent) => onDragStart(e, role.id)}
					ondragover={(e: DragEvent) => onDragOver(e, role.id)}
					ondragleave={onDragLeave}
					ondragend={onDragEnd}
					ondrop={(e: DragEvent) => onDrop(e, role.id)}
				>
					{#if canManageRoles}
						<span class="drag-handle material-symbols-outlined">drag_indicator</span>
					{/if}
					<span class="role-badge" style="background-color: {role.color || '#99aab5'}">&nbsp;</span>
					<span class="role-name">{role.name}</span>
					{#if role.id === 'owner'}
						<span class="protected-badge">Owner</span>
					{:else if role.is_default}
						<span class="default-badge">Default</span>
					{/if}
					{#if canManageRoles && role.id !== 'owner'}
						{#if !role.is_default}
							<button class="btn-sm btn-default" onclick={() => handleSetDefault(role.id)} disabled={loading}>Set as Default</button>
						{/if}
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
				</div>

				<h3>Permissions</h3>
				<div class="perm-sections">
					{#each Object.entries(PERMISSION_GROUPS) as [group, perms]}
						<div class="perm-group">
							<h4>{group}</h4>
							<div class="perm-list">
								{#each perms as perm}
									<label
										class="perm-toggle"
										class:checked={isPermChecked(perm.name)}
										class:forced={isPermDisabled(perm.name)}
									>
										<input
											type="checkbox"
											checked={isPermChecked(perm.name)}
											disabled={isPermDisabled(perm.name)}
											onchange={() => togglePerm(perm.name)}
										/>
										<span class="perm-label">{perm.label}</span>
										<span class="perm-desc">{perm.desc}</span>
									</label>
								{/each}
							</div>
						</div>
					{/each}
				</div>

				<div class="form-actions">
					<button class="btn-primary" onclick={handleSave} disabled={loading}>
						{loading ? 'Saving...' : 'Save'}
					</button>
					<button class="btn-sm" onclick={cancelEdit}>Cancel</button>
				</div>
			</div>
		{/if}
</div>

<style>
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
		transition: background 0.1s, opacity 0.1s;
	}

	.role-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.03));
	}

	.role-item.active {
		background: var(--bg-active, rgba(255, 255, 255, 0.06));
	}

	.role-item.dragging {
		opacity: 0.4;
	}

	.role-item.drag-over {
		box-shadow: 0 -2px 0 0 var(--accent, #5865f2);
	}

	.drag-handle {
		font-size: 1.1rem;
		color: var(--text-muted);
		cursor: grab;
		user-select: none;
		flex-shrink: 0;
	}

	.drag-handle:active {
		cursor: grabbing;
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

	.form-group input[type='color'] {
		width: 40px;
		height: 32px;
		border: 1px solid var(--border);
		border-radius: 4px;
		cursor: pointer;
		padding: 2px;
		appearance: none;
		-webkit-appearance: none;
		background: none;
	}

	.form-group input[type='color']::-webkit-color-swatch-wrapper {
		padding: 0;
	}

	.form-group input[type='color']::-webkit-color-swatch {
		border: none;
		border-radius: 2px;
	}

	h2 {
		font-size: 1rem;
		margin: 0 0 0.75rem;
	}

	h3 {
		font-size: 0.9rem;
		margin: 0.75rem 0 0.5rem;
	}

	.perm-sections {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.perm-group {
		background: var(--bg, rgba(0, 0, 0, 0.15));
		border: 1px solid var(--border);
		border-radius: 6px;
		padding: 0.75rem;
	}

	.perm-group h4 {
		font-size: 0.7rem;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--text-muted);
		margin: 0 0 0.5rem;
		padding-bottom: 0.4rem;
		border-bottom: 1px solid var(--border);
	}

	.perm-list {
		display: grid;
		grid-template-columns: auto 160px 1fr;
		gap: 0;
	}

	.perm-toggle {
		display: grid;
		grid-template-columns: subgrid;
		grid-column: 1 / -1;
		align-items: center;
		gap: 0.5rem;
		padding: 0.35rem 0.4rem;
		font-size: 0.85rem;
		cursor: pointer;
		border-radius: 4px;
		transition: background 0.1s;
	}

	.perm-toggle:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.04));
	}

	.perm-toggle.checked {
		background: rgba(88, 101, 242, 0.08);
	}

	.perm-toggle.checked:hover {
		background: rgba(88, 101, 242, 0.12);
	}

	.perm-toggle.forced {
		opacity: 0.55;
		cursor: default;
	}

	.perm-toggle input[type='checkbox'] {
		width: auto;
		flex-shrink: 0;
	}

	.perm-label {
		font-weight: 500;
		white-space: nowrap;
	}

	.perm-desc {
		color: var(--text-muted);
		font-size: 0.75rem;
	}

	.default-hint {
		color: var(--text-muted);
		font-size: 0.8rem;
		margin-bottom: 0.75rem;
	}

	.btn-default {
		border-color: var(--accent, #5865f2);
		color: var(--accent, #5865f2);
	}

	.btn-default:hover {
		background: rgba(88, 101, 242, 0.1);
		color: var(--accent, #5865f2);
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

</style>
