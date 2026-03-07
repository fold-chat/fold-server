<script lang="ts">
	import { getMembers, banMember, unbanMember, type Member } from '$lib/api/users.js';
	import { assignRole, removeRole } from '$lib/api/roles.js';
	import { getVoiceModeration, setServerMuteGlobal, clearServerMute, setServerDeafGlobal, clearServerDeaf, type VoiceModeration } from '$lib/api/voice.js';
	import { getRolesList } from '$lib/stores/roles.svelte.js';
	import { hasServerPermission, getUser } from '$lib/stores/auth.svelte.js';
	import { openMemberProfile } from '$lib/stores/membersPanel.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';

	const PAGE_SIZE = 10;

	let members = $state<Member[]>([]);
	let moderation = $state<Map<string, VoiceModeration>>(new Map());
	let loading = $state(true);
	let error = $state('');
	let editingMember = $state<string | null>(null);
	let confirmAction = $state<{ type: 'ban' | 'unban'; member: Member } | null>(null);
	let banReason = $state('');

	// Filters
	let search = $state('');
	let roleFilter = $state('');
	let bannedFilter = $state<'all' | 'active' | 'banned'>('all');
	let currentPage = $state(1);

	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));
	const canBan = $derived(hasServerPermission(PermissionName.BAN_MEMBERS));
	const canMute = $derived(hasServerPermission(PermissionName.MUTE_MEMBERS));
	const canDeafen = $derived(hasServerPermission(PermissionName.DEAFEN_MEMBERS));
	const canEdit = $derived(canManageRoles || canMute || canDeafen || canBan);
	const currentUser = $derived(getUser());

	const filteredMembers = $derived.by(() => {
		let result = members;
		const q = search.toLowerCase().trim();
		if (q) {
			result = result.filter(m =>
				m.username.toLowerCase().includes(q) ||
				(m.display_name?.toLowerCase().includes(q) ?? false)
			);
		}
		if (roleFilter) {
			result = result.filter(m => parseMemberRoles(m).some(r => r.id === roleFilter));
		}
		if (bannedFilter === 'active') {
			result = result.filter(m => !isBanned(m));
		} else if (bannedFilter === 'banned') {
			result = result.filter(m => isBanned(m));
		}
		return result;
	});

	const totalPages = $derived(Math.max(1, Math.ceil(filteredMembers.length / PAGE_SIZE)));
	const pageMembers = $derived(filteredMembers.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE));
	const showingFrom = $derived(filteredMembers.length === 0 ? 0 : (currentPage - 1) * PAGE_SIZE + 1);
	const showingTo = $derived(Math.min(currentPage * PAGE_SIZE, filteredMembers.length));

	// Reset page when filters change
	$effect(() => {
		search; roleFilter; bannedFilter;
		currentPage = 1;
	});

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

	function formatDate(dateStr: string): string {
		try {
			const d = new Date(dateStr + 'Z');
			return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
		} catch {
			return dateStr;
		}
	}

	function formatDateTime(dateStr: string): string {
		try {
			const d = new Date(dateStr + 'Z');
			return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
				+ ' at ' + d.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
		} catch {
			return dateStr;
		}
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

	function toggleEdit(memberId: string) {
		editingMember = editingMember === memberId ? null : memberId;
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
		<div class="toolbar">
			<input
				type="text"
				class="search-input"
				placeholder="Search members..."
				bind:value={search}
			/>
			<select class="filter-select" bind:value={roleFilter}>
				<option value="">All Roles</option>
				{#each getRolesList() as role}
					<option value={role.id}>{role.name}</option>
				{/each}
			</select>
			{#if canBan}
				<select class="filter-select" bind:value={bannedFilter}>
					<option value="all">All</option>
					<option value="active">Active</option>
					<option value="banned">Banned</option>
				</select>
			{/if}
		</div>

		<table class="members-table">
			<thead>
				<tr>
					<th class="col-user">User</th>
					<th class="col-joined">Joined</th>
					<th class="col-roles">Roles</th>
					{#if canEdit}
						<th class="col-edit"></th>
					{/if}
				</tr>
			</thead>
			<tbody>
				{#each pageMembers as member}
					<tr class="member-row" class:banned={isBanned(member)} title={isBanned(member) ? banTooltip(member) : ''}>
						<td class="col-user">
							<!-- svelte-ignore a11y_click_events_have_key_events -->
							<!-- svelte-ignore a11y_no_static_element_interactions -->
							<div class="user-cell" onclick={() => openMemberProfile(member.id)}>
								{#if member.avatar_url}
									<img src={member.avatar_url} alt="" class="avatar" class:banned-avatar={isBanned(member)} />
								{:else}
									<div class="avatar placeholder" class:banned-avatar={isBanned(member)}>{member.username.charAt(0).toUpperCase()}</div>
								{/if}
								<div class="user-info">
									<span class="member-name" class:banned-text={isBanned(member)}>
										{member.display_name || member.username}
										{#if isBanned(member)}
											<span class="banned-badge">Banned</span>
										{/if}
									</span>
									{#if member.display_name}
										<span class="member-username">@{member.username}</span>
									{/if}
								</div>
							</div>
						</td>
						<td class="col-joined">
							<span class="joined-date">{formatDate(member.created_at)}</span>
							<span class="join-method" title={joinMethodTooltip(member)}>{joinMethodLabel(member.join_method)}</span>
						</td>
						<td class="col-roles">
							{#if !isBanned(member)}
								<div class="roles-cell">
									{#each parseMemberRoles(member) as role}
										<span class="role-chip" style="border-color: {role.color || '#99aab5'}">
											<span class="role-dot" style="background: {role.color || '#99aab5'}"></span>
											{role.name}
										</span>
									{/each}
								</div>
							{/if}
						</td>
						{#if canEdit}
							<td class="col-edit">
								<button class="edit-btn" class:active={editingMember === member.id} onclick={() => toggleEdit(member.id)} title="Edit member">
									<span class="material-symbols-outlined">edit</span>
								</button>
							</td>
						{/if}
					</tr>
					{#if editingMember === member.id}
						<tr class="expand-row">
							<td colspan={canEdit ? 4 : 3}>
								<div class="edit-panel">
									{#if canManageRoles && !isBanned(member)}
										<div class="edit-group">
											<div class="edit-group-label">Roles</div>
											<div class="edit-roles">
												{#each getRolesList() as role}
													{#if role.id !== 'owner'}
														<label class="edit-role-item">
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
															<span class="edit-role-dot" style="background: {role.color || '#99aab5'}"></span>
															<span class="edit-role-name">{role.name}</span>
														</label>
													{/if}
												{/each}
											</div>
										</div>
									{/if}
									{#if !isBanned(member) && currentUser?.id !== member.id && (canMute || canDeafen)}
										<div class="edit-group">
											<div class="edit-group-label">Voice Moderation</div>
											<div class="edit-btn-row">
												{#if canMute}
													<button class="btn-sm" class:btn-active={isServerMuted(member)} onclick={() => handleToggleMute(member)}>
														{isServerMuted(member) ? 'Unmute' : 'Server Mute'}
													</button>
												{/if}
												{#if canDeafen}
													<button class="btn-sm" class:btn-active={isServerDeafened(member)} onclick={() => handleToggleDeafen(member)}>
														{isServerDeafened(member) ? 'Undeafen' : 'Server Deafen'}
													</button>
												{/if}
											</div>
										</div>
									{/if}
									{#if isBanned(member) && canBan}
										<div class="edit-group">
											<div class="edit-group-label">Ban Info</div>
											<div class="ban-info">
												{#if member.banned_at}
													<div class="ban-info-row"><span class="ban-info-label">Banned</span> {formatDateTime(member.banned_at)}</div>
												{/if}
												{#if member.banned_by_username}
													<div class="ban-info-row"><span class="ban-info-label">By</span> {member.banned_by_username}</div>
												{/if}
												{#if member.ban_reason}
													<div class="ban-info-row"><span class="ban-info-label">Reason</span> {member.ban_reason}</div>
												{:else}
													<div class="ban-info-row"><span class="ban-info-label">Reason</span> <span class="ban-info-none">No reason given</span></div>
												{/if}
											</div>
										</div>
									{/if}
									{#if !isOwner(member) && currentUser?.id !== member.id && canBan}
										<div class="edit-group">
											<div class="edit-btn-row">
												{#if isBanned(member)}
													<button class="btn-sm" onclick={() => promptUnban(member)}>Unban</button>
												{:else}
													<button class="btn-sm btn-danger" onclick={() => promptBan(member)}>Ban</button>
												{/if}
											</div>
										</div>
									{/if}
								</div>
							</td>
						</tr>
					{/if}
				{/each}
			</tbody>
		</table>

		<div class="pagination">
			<span class="pagination-info">
				{#if filteredMembers.length > 0}
					Showing {showingFrom} to {showingTo} of {filteredMembers.length} members
				{:else}
					No members found
				{/if}
			</span>
			<div class="pagination-buttons">
				<button class="btn-sm" disabled={currentPage <= 1} onclick={() => currentPage--}>Previous</button>
				<button class="btn-sm" disabled={currentPage >= totalPages} onclick={() => currentPage++}>Next</button>
			</div>
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
	/* Toolbar */
	.toolbar {
		display: flex;
		gap: 0.5rem;
		margin-bottom: 1rem;
		flex-wrap: wrap;
	}

	.search-input {
		flex: 1;
		min-width: 150px;
		padding: 0.4rem 0.6rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 4px;
		color: var(--text);
		font-size: 0.85rem;
		font-family: inherit;
	}

	.search-input::placeholder {
		color: var(--text-muted);
	}

	.filter-select {
		padding: 0.4rem 0.6rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 4px;
		color: var(--text);
		font-size: 0.85rem;
		font-family: inherit;
		cursor: pointer;
	}

	/* Table */
	.members-table {
		width: 100%;
		border-collapse: collapse;
		font-size: 0.85rem;
	}

	.members-table thead th {
		text-align: left;
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
		padding: 0.5rem 0.5rem;
		border-bottom: 1px solid var(--border);
	}

	.members-table tbody td {
		padding: 0.5rem;
		vertical-align: middle;
		border-bottom: 1px solid rgba(255, 255, 255, 0.03);
	}

	.member-row:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.03));
	}

	.col-user {
		width: 35%;
	}

	.col-joined {
		width: 20%;
	}

	.col-roles {
		width: 35%;
	}

	.col-edit {
		width: 10%;
		text-align: center;
	}

	/* User cell */
	.user-cell {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		cursor: pointer;
	}

	.user-cell:hover .member-name {
		color: var(--accent, #5865f2);
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

	.user-info {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.member-name {
		font-weight: 500;
		font-size: 0.85rem;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.member-username {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	/* Joined column */
	.joined-date {
		display: block;
		font-size: 0.8rem;
	}

	.join-method {
		display: block;
		font-size: 0.65rem;
		color: var(--text-muted);
		cursor: default;
	}

	/* Roles column */
	.roles-cell {
		display: flex;
		gap: 0.3rem;
		flex-wrap: wrap;
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

	/* Edit button */
	.edit-btn {
		background: none;
		border: 1px solid var(--border);
		border-radius: 4px;
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.2rem;
		display: inline-flex;
		align-items: center;
		justify-content: center;
	}

	.edit-btn:hover,
	.edit-btn.active {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.edit-btn .material-symbols-outlined {
		font-size: 16px;
	}

	/* Edit panel (expand row) */
	.expand-row td {
		padding: 0 !important;
		border-bottom: 1px solid var(--border);
	}

	.edit-panel {
		padding: 0.75rem 1rem;
		background: var(--bg, rgba(0, 0, 0, 0.2));
		border-top: 1px solid var(--border);
	}

	.edit-group {
		margin-bottom: 0.75rem;
	}

	.edit-group:last-child {
		margin-bottom: 0;
	}

	.edit-group-label {
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
		margin-bottom: 0.4rem;
	}

	.edit-roles {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
		gap: 0.4rem 1rem;
	}

	.edit-role-item {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		font-size: 0.8rem;
		cursor: pointer;
		white-space: nowrap;
	}

	.edit-role-item input[type='checkbox'] {
		width: auto;
		flex-shrink: 0;
	}

	.edit-role-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		flex-shrink: 0;
	}

	.edit-role-name {
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.edit-btn-row {
		display: flex;
		gap: 0.5rem;
		flex-wrap: wrap;
		align-items: center;
	}

	.ban-info {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		font-size: 0.8rem;
	}

	.ban-info-row {
		color: var(--text);
	}

	.ban-info-label {
		color: var(--text-muted);
		margin-right: 0.35rem;
	}

	.ban-info-none {
		color: var(--text-muted);
		font-style: italic;
	}

	/* Pagination */
	.pagination {
		display: flex;
		justify-content: space-between;
		align-items: center;
		margin-top: 0.75rem;
		padding-top: 0.75rem;
	}

	.pagination-info {
		font-size: 0.8rem;
		color: var(--text-muted);
	}

	.pagination-buttons {
		display: flex;
		gap: 0.35rem;
	}

	/* Banned states */
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
		font-size: 0.6rem;
		color: #e74c3c;
		background: rgba(231, 76, 60, 0.15);
		padding: 0.05rem 0.3rem;
		border-radius: 3px;
		margin-left: 0.3rem;
		font-weight: 500;
	}

	.btn-active {
		color: #e67e22;
		border-color: rgba(230, 126, 34, 0.4);
	}

	/* Modal */
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
</style>
