<script lang="ts">
	import { getMembers, type Member } from '$lib/api/users.js';
	import { assignRole, removeRole } from '$lib/api/roles.js';
	import { getRolesList } from '$lib/stores/roles.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';

	let members = $state<Member[]>([]);
	let loading = $state(true);
	let error = $state('');
	let editingMember = $state<string | null>(null);

	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));

	onMount(async () => {
		try {
			members = await getMembers();
		} catch {
			error = 'Failed to load members';
		} finally {
			loading = false;
		}
	});

	function parseMemberRoles(member: Member) {
		if (!member.roles) return [];
		// roles comes as JSON string from SQLite or already parsed array
		if (typeof member.roles === 'string') {
			try {
				const parsed = JSON.parse(member.roles);
				return Array.isArray(parsed) ? parsed.filter((r: { id: string | null }) => r.id !== null) : [];
			} catch {
				return [];
			}
		}
		return Array.isArray(member.roles) ? member.roles.filter((r) => r.id !== null) : [];
	}

	async function handleAssign(userId: string, roleId: string) {
		error = '';
		try {
			await assignRole(userId, roleId);
			// Refresh members
			members = await getMembers();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to assign role';
		}
	}

	async function handleRemove(userId: string, roleId: string) {
		error = '';
		try {
			await removeRole(userId, roleId);
			members = await getMembers();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to remove role';
		}
	}

	function memberHasRole(member: Member, roleId: string): boolean {
		return parseMemberRoles(member).some((r: { id: string }) => r.id === roleId);
	}
</script>

<div class="settings-page">
	<div class="settings-card">
		<div class="header-row">
			<h1>Members</h1>
			<p><a href="/">&larr; Back</a></p>
		</div>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		{#if loading}
			<p class="muted">Loading...</p>
		{:else}
			<div class="member-list">
				{#each members as member}
					<div class="member-item">
						<div class="member-info">
							{#if member.avatar_url}
								<img src={member.avatar_url} alt="" class="avatar" />
							{:else}
								<div class="avatar placeholder">{member.username.charAt(0).toUpperCase()}</div>
							{/if}
							<div class="member-details">
								<span class="member-name">{member.display_name || member.username}</span>
								{#if member.display_name}
									<span class="member-username">@{member.username}</span>
								{/if}
							</div>
							<div class="member-roles">
								{#each parseMemberRoles(member) as role}
									<span class="role-chip" style="border-color: {role.color || '#99aab5'}">
										<span class="role-dot" style="background: {role.color || '#99aab5'}"></span>
										{role.name}
									</span>
								{/each}
							</div>
							{#if canManageRoles}
								<button class="btn-sm" onclick={() => (editingMember = editingMember === member.id ? null : member.id)}>
									{editingMember === member.id ? 'Close' : 'Roles'}
								</button>
							{/if}
						</div>

						{#if editingMember === member.id && canManageRoles}
							<div class="role-editor">
								{#each getRolesList() as role}
									{#if role.id !== 'owner'}
										<label class="role-toggle">
											<input
												type="checkbox"
												checked={memberHasRole(member, role.id)}
												onchange={() => {
													if (memberHasRole(member, role.id)) {
														handleRemove(member.id, role.id);
													} else {
														handleAssign(member.id, role.id);
													}
												}}
											/>
											<span class="role-dot" style="background: {role.color || '#99aab5'}"></span>
											{role.name}
										</label>
									{/if}
								{/each}
							</div>
						{/if}
					</div>
				{/each}
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

	.member-list {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.member-item {
		border-radius: 4px;
		padding: 0.5rem 0.75rem;
	}

	.member-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.03));
	}

	.member-info {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.avatar {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.avatar.placeholder {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--accent, #5865f2);
		color: white;
		font-size: 0.85rem;
		font-weight: 600;
	}

	.member-details {
		display: flex;
		flex-direction: column;
		min-width: 120px;
	}

	.member-name {
		font-weight: 500;
		font-size: 0.875rem;
	}

	.member-username {
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.member-roles {
		display: flex;
		gap: 0.35rem;
		flex-wrap: wrap;
		flex: 1;
	}

	.role-chip {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		font-size: 0.7rem;
		padding: 0.1rem 0.4rem;
		border: 1px solid;
		border-radius: 3px;
		font-weight: 500;
	}

	.role-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		flex-shrink: 0;
	}

	.role-editor {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin-top: 0.5rem;
		padding: 0.5rem;
		background: var(--bg, rgba(0, 0, 0, 0.2));
		border-radius: 4px;
	}

	.role-toggle {
		display: flex;
		align-items: center;
		gap: 0.35rem;
		font-size: 0.8rem;
		cursor: pointer;
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

	.error-message {
		color: #e74c3c;
		font-size: 0.85rem;
		margin-bottom: 0.75rem;
		padding: 0.5rem;
		background: rgba(231, 76, 60, 0.1);
		border-radius: 4px;
	}
</style>
