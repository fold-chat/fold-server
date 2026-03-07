<script lang="ts">
	import { getMembers, banMember, unbanMember, type Member } from '$lib/api/users.js';
	import { assignRole, removeRole } from '$lib/api/roles.js';
	import { getVoiceModeration, setServerMuteGlobal, clearServerMute, setServerDeafGlobal, clearServerDeaf, type VoiceModeration } from '$lib/api/voice.js';
	import { getRolesList } from '$lib/stores/roles.svelte.js';
	import { hasServerPermission, getUser } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';

	let members = $state<Member[]>([]);
	let moderation = $state<Map<string, VoiceModeration>>(new Map());
	let loading = $state(true);
	let error = $state('');
	let editingMember = $state<string | null>(null);
	let confirmAction = $state<{ type: 'ban' | 'unban'; member: Member } | null>(null);
	let banReason = $state('');

	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));
	const canBan = $derived(hasServerPermission(PermissionName.BAN_MEMBERS));
	const canMute = $derived(hasServerPermission(PermissionName.MUTE_MEMBERS));
	const canDeafen = $derived(hasServerPermission(PermissionName.DEAFEN_MEMBERS));
	const currentUser = $derived(getUser());

	async function loadModeration() {
		try {
			const list = await getVoiceModeration();
			moderation = new Map(list.map((m) => [m.user_id, m]));
		} catch {
			// User may not have permission — ignore
		}
	}

	onMount(async () => {
		try {
			members = await getMembers();
			if (hasServerPermission(PermissionName.MUTE_MEMBERS) || hasServerPermission(PermissionName.DEAFEN_MEMBERS)) {
				await loadModeration();
			}
		} catch {
			error = 'Failed to load members';
		} finally {
			loading = false;
		}
	});

	function isServerMuted(member: Member): boolean {
		return (moderation.get(member.id)?.server_mute ?? 0) !== 0;
	}

	function isServerDeafened(member: Member): boolean {
		return (moderation.get(member.id)?.server_deaf ?? 0) !== 0;
	}

	async function handleToggleMute(member: Member) {
		error = '';
		try {
			if (isServerMuted(member)) {
				await clearServerMute(member.id);
			} else {
				await setServerMuteGlobal(member.id);
			}
			await loadModeration();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to update mute';
		}
	}

	async function handleToggleDeafen(member: Member) {
		error = '';
		try {
			if (isServerDeafened(member)) {
				await clearServerDeaf(member.id);
			} else {
				await setServerDeafGlobal(member.id);
			}
			await loadModeration();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to update deafen';
		}
	}

	function parseMemberRoles(member: Member) {
		if (!member.roles) return [];
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

	function isOwner(member: Member): boolean {
		return parseMemberRoles(member).some((r: { id: string }) => r.id === 'owner');
	}

	function isBanned(member: Member): boolean {
		return member.banned_at != null;
	}

	function joinMethodLabel(method: string | null): string {
		switch (method) {
			case 'invite': return 'Invite';
			case 'registration': return 'Registration';
			case 'setup': return 'Setup';
			default: return 'Unknown';
		}
	}

	function joinMethodTooltip(member: Member): string {
		const label = joinMethodLabel(member.join_method);
		if (member.join_method === 'invite' && member.invite_description) {
			return `Joined via ${label}: ${member.invite_description}`;
		}
		return `Joined via ${label}`;
	}

	function banTooltip(member: Member): string {
		const parts = ['Banned'];
		if (member.banned_by_username) parts.push(`by ${member.banned_by_username}`);
		if (member.banned_at) {
			const date = new Date(member.banned_at + 'Z');
			parts.push(`on ${date.toLocaleDateString()}`);
		}
		if (member.ban_reason) parts.push(`— ${member.ban_reason}`);
		return parts.join(' ');
	}

	function promptBan(member: Member) {
		confirmAction = { type: 'ban', member };
		banReason = '';
	}

	function promptUnban(member: Member) {
		confirmAction = { type: 'unban', member };
		banReason = '';
	}

	async function executeAction() {
		if (!confirmAction) return;
		error = '';
		try {
			if (confirmAction.type === 'ban') {
				await banMember(confirmAction.member.id, banReason || undefined);
			} else {
				await unbanMember(confirmAction.member.id);
			}
			members = await getMembers();
		} catch (err) {
			error = (err as ApiError).message || `Failed to ${confirmAction.type} member`;
		} finally {
			confirmAction = null;
			banReason = '';
		}
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Members</h1>
	</div>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		{#if loading}
			<p class="muted">Loading...</p>
		{:else}
			<div class="member-list">
				{#each members as member}
					<div class="member-item" class:banned={isBanned(member)} title={isBanned(member) ? banTooltip(member) : ''}>
						<div class="member-info">
							{#if member.avatar_url}
								<img src={member.avatar_url} alt="" class="avatar" class:banned-avatar={isBanned(member)} />
							{:else}
								<div class="avatar placeholder" class:banned-avatar={isBanned(member)}>{member.username.charAt(0).toUpperCase()}</div>
							{/if}
							<div class="member-details">
								<span class="member-name" class:banned-text={isBanned(member)}>
									{member.display_name || member.username}
								{#if isBanned(member)}
										<span class="banned-badge">Banned</span>
									{/if}
									{#if isServerMuted(member)}
										<span class="mod-badge muted-badge">Server Muted</span>
									{/if}
									{#if isServerDeafened(member)}
										<span class="mod-badge deafened-badge">Server Deafened</span>
									{/if}
								</span>
							{#if member.display_name}
								<span class="member-username">@{member.username}</span>
							{/if}
							<span class="join-badge" class:join-invite={member.join_method === 'invite'} class:join-setup={member.join_method === 'setup'} title={joinMethodTooltip(member)}>
								{joinMethodLabel(member.join_method)}
							</span>
							</div>
							{#if !isBanned(member)}
								<div class="member-roles">
									{#each parseMemberRoles(member) as role}
										<span class="role-chip" style="border-color: {role.color || '#99aab5'}">
											<span class="role-dot" style="background: {role.color || '#99aab5'}"></span>
											{role.name}
										</span>
									{/each}
								</div>
							{/if}
							<div class="member-actions">
								{#if !isBanned(member) && canManageRoles}
									<button class="btn-sm" onclick={() => (editingMember = editingMember === member.id ? null : member.id)}>
										{editingMember === member.id ? 'Close' : 'Roles'}
									</button>
								{/if}
								{#if !isBanned(member) && currentUser?.id !== member.id}
									{#if canMute}
										<button class="btn-sm" class:btn-active={isServerMuted(member)} onclick={() => handleToggleMute(member)}>
											{isServerMuted(member) ? 'Unmute' : 'Mute'}
										</button>
									{/if}
									{#if canDeafen}
										<button class="btn-sm" class:btn-active={isServerDeafened(member)} onclick={() => handleToggleDeafen(member)}>
											{isServerDeafened(member) ? 'Undeafen' : 'Deafen'}
										</button>
									{/if}
								{/if}
								{#if !isOwner(member) && currentUser?.id !== member.id && canBan}
									{#if isBanned(member)}
										<button class="btn-sm" onclick={() => promptUnban(member)}>Unban</button>
									{:else}
										<button class="btn-sm btn-danger" onclick={() => promptBan(member)}>Ban</button>
									{/if}
								{/if}
							</div>
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

{#if confirmAction}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="modal-overlay" onkeydown={(e) => e.key === 'Escape' && (confirmAction = null)} onclick={() => (confirmAction = null)}>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="modal" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.key === 'Escape' && (confirmAction = null)}>
			<h2>{confirmAction.type === 'ban' ? 'Ban' : 'Unban'} {confirmAction.member.display_name || confirmAction.member.username}?</h2>
			{#if confirmAction.type === 'ban'}
				<p class="modal-desc">This user will be permanently banned and unable to rejoin.</p>
				<div class="form-group">
					<label for="ban-reason">Reason (optional)</label>
					<input id="ban-reason" type="text" bind:value={banReason} placeholder="Reason for ban" />
				</div>
			{:else}
				<p class="modal-desc">This user will be unbanned and able to rejoin.</p>
			{/if}
			<div class="modal-actions">
				<button class="btn-sm" onclick={() => (confirmAction = null)}>Cancel</button>
				<button class="btn-sm {confirmAction.type === 'ban' ? 'btn-danger' : ''}" onclick={executeAction}>
					{confirmAction.type === 'ban' ? 'Ban' : 'Unban'}
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
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

	.member-actions {
		display: flex;
		gap: 0.35rem;
		align-items: center;
		flex-shrink: 0;
	}

	.modal-overlay {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 100;
	}

	.modal {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 1.5rem;
		min-width: 320px;
		max-width: 420px;
	}

	.modal h2 {
		margin: 0 0 0.5rem;
		font-size: 1.1rem;
	}

	.modal-desc {
		color: var(--text-muted);
		font-size: 0.85rem;
		margin: 0 0 1rem;
	}

	.modal .form-group {
		margin-bottom: 1rem;
	}

	.modal .form-group label {
		display: block;
		font-size: 0.8rem;
		color: var(--text-muted);
		margin-bottom: 0.25rem;
	}

	.modal .form-group input {
		width: 100%;
		padding: 0.5rem 0.6rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		background: var(--bg);
		color: var(--text);
		font-size: 0.875rem;
	}

	.modal-actions {
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
	}

	.banned {
		opacity: 0.55;
	}

	.banned:hover {
		opacity: 0.8;
	}

	.banned-text {
		color: var(--text-muted) !important;
	}

	.banned-avatar {
		filter: grayscale(100%);
	}

	.banned-badge {
		font-size: 0.65rem;
		color: #e74c3c;
		background: rgba(231, 76, 60, 0.15);
		padding: 0.05rem 0.3rem;
		border-radius: 3px;
		margin-left: 0.35rem;
		font-weight: 500;
	}

	.mod-badge {
		font-size: 0.6rem;
		padding: 0.05rem 0.3rem;
		border-radius: 3px;
		margin-left: 0.35rem;
		font-weight: 500;
	}

	.muted-badge {
		color: #e67e22;
		background: rgba(230, 126, 34, 0.15);
	}

	.deafened-badge {
		color: #e74c3c;
		background: rgba(231, 76, 60, 0.15);
	}

	.btn-active {
		color: #e67e22;
		border-color: rgba(230, 126, 34, 0.4);
	}

	.join-badge {
		font-size: 0.6rem;
		color: var(--text-muted);
		background: rgba(255, 255, 255, 0.05);
		padding: 0.05rem 0.35rem;
		border-radius: 3px;
		margin-left: 0.35rem;
		font-weight: 500;
		cursor: default;
	}

	.join-badge.join-invite {
		color: #3498db;
		background: rgba(52, 152, 219, 0.12);
	}

	.join-badge.join-setup {
		color: #f39c12;
		background: rgba(243, 156, 18, 0.12);
	}

</style>
