<script lang="ts">
	import { getMembers } from '$lib/stores/members.svelte.js';
	import { getPendingUserId, clearPendingUserId } from '$lib/stores/membersPanel.svelte.js';
	import { isUserOnline, getOnlineCount } from '$lib/stores/presence.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { Member, RoleBadge } from '$lib/api/users.js';

	let selectedMember = $state<Member | null>(null);

	// Auto-select member when pendingUserId is set (e.g. from mention click)
	$effect(() => {
		const uid = getPendingUserId();
		if (uid) {
			const member = getMembers().find(m => m.id === uid);
			if (member) selectedMember = member;
			clearPendingUserId();
		}
	});

	// Role priority: owner > admin > moderator > member
	const ROLE_PRIORITY: Record<string, number> = {
		owner: 0,
		admin: 1,
		moderator: 2,
		member: 3
	};

	function rolePriority(role: RoleBadge): number {
		return ROLE_PRIORITY[role.name?.toLowerCase()] ?? 99;
	}

	/** Roles arrive as a JSON string from the server; parse and filter nulls */
	function parseRoles(raw: unknown): RoleBadge[] {
		let arr: RoleBadge[] = [];
		if (typeof raw === 'string') {
			try { arr = JSON.parse(raw); } catch { return []; }
		} else if (Array.isArray(raw)) {
			arr = raw;
		}
		return arr.filter(r => r && r.id != null && r.name != null);
	}

	interface RoleGroup {
		role: RoleBadge;
		members: Member[];
	}

	const groupedMembers = $derived.by(() => {
		const members = getMembers().filter(m => !m.banned_at);
		const roleMap = new Map<string, { role: RoleBadge; members: Member[] }>();

		for (const m of members) {
			const roles = parseRoles(m.roles);
			// Use highest-priority role for grouping
			const topRole = roles.length
				? [...roles].sort((a, b) => rolePriority(a) - rolePriority(b))[0]
				: { id: '_none', name: 'Member', color: null };

			let group = roleMap.get(topRole.id);
			if (!group) {
				group = { role: topRole, members: [] };
				roleMap.set(topRole.id, group);
			}
			group.members.push(m);
		}

		// Sort members within each group: online first, then alphabetically
		for (const group of roleMap.values()) {
			group.members.sort((a, b) => {
				const aOnline = isUserOnline(a.id) ? 0 : 1;
				const bOnline = isUserOnline(b.id) ? 0 : 1;
				if (aOnline !== bOnline) return aOnline - bOnline;
				return (a.display_name || a.username).localeCompare(b.display_name || b.username);
			});
		}

		// Sort groups by role priority
		return [...roleMap.values()].sort((a, b) => rolePriority(a.role) - rolePriority(b.role));
	});

	function selectMember(m: Member) {
		selectedMember = m;
	}

	function goBack() {
		selectedMember = null;
	}

	const selectedMemberRoles = $derived(selectedMember ? parseRoles(selectedMember.roles) : []);

	function formatDate(dateStr: string | null): string {
		if (!dateStr) return 'Unknown';
		try {
			return new Date(dateStr).toLocaleDateString(undefined, { year: 'numeric', month: 'short', day: 'numeric' });
		} catch {
			return dateStr;
		}
	}

	function relativeTime(dateStr: string | null): string {
		if (!dateStr) return 'Unknown';
		try {
			const now = Date.now();
			const then = new Date(dateStr + 'Z').getTime();
			const diffSec = Math.floor((now - then) / 1000);
			if (diffSec < 60) return 'Just now';
			if (diffSec < 3600) return `${Math.floor(diffSec / 60)}m ago`;
			if (diffSec < 86400) return `${Math.floor(diffSec / 3600)}h ago`;
			if (diffSec < 172800) return 'Yesterday';
			return formatDate(dateStr);
		} catch {
			return dateStr;
		}
	}

	const canManageInvites = $derived(hasServerPermission(PermissionName.MANAGE_INVITES));

	function joinMethodLabel(method: string | null): string {
		switch (method) {
			case 'invite': return 'Invite';
			case 'registration': return 'Registration';
			case 'setup': return 'Setup';
			default: return 'Unknown';
		}
	}

	const activeMembers = $derived(getMembers().filter(m => !m.banned_at));
	const onlineCount = $derived.by(() => {
		let count = 0;
		for (const m of activeMembers) {
			if (isUserOnline(m.id)) count++;
		}
		return count;
	});
</script>

<aside class="members-panel">
	{#if selectedMember}
		<div class="profile-view">
			<button class="back-btn" onclick={goBack}>← Back</button>
			<div class="profile-header">
				{#if selectedMember.avatar_url}
					<img class="profile-avatar" src={selectedMember.avatar_url} alt="" />
				{:else}
					<span class="profile-avatar-placeholder">{(selectedMember.display_name || selectedMember.username).charAt(0).toUpperCase()}</span>
				{/if}
				<div class="profile-names">
					{#if selectedMember.display_name}
						<span class="profile-display">{selectedMember.display_name}</span>
					{/if}
					<span class="profile-username">@{selectedMember.username}</span>
				</div>
			</div>
			<div class="profile-presence">
				<span class="presence-dot" class:online={isUserOnline(selectedMember.id)} class:offline={!isUserOnline(selectedMember.id)}></span>
				{#if isUserOnline(selectedMember.id)}
					<span class="presence-label online">Online</span>
				{:else}
					<span class="presence-label offline">Offline</span>
					{#if selectedMember.last_seen_at}
						<span class="last-seen"> · Last seen {relativeTime(selectedMember.last_seen_at)}</span>
					{/if}
				{/if}
			</div>
			{#if selectedMember.status_text}
				<div class="profile-field">
					<span class="field-label">Status</span>
					<span class="field-value">{selectedMember.status_text}</span>
				</div>
			{/if}
			{#if selectedMember.bio}
				<div class="profile-field">
					<span class="field-label">Bio</span>
					<span class="field-value">{selectedMember.bio}</span>
				</div>
			{/if}
		{#if selectedMemberRoles.length}
			<div class="profile-field">
				<span class="field-label">Roles</span>
				<div class="profile-roles">
					{#each selectedMemberRoles as role}
						<span class="role-tag" style:color={role.color || 'var(--text-muted)'}>{role.name}</span>
					{/each}
				</div>
			</div>
		{/if}
			<div class="profile-field">
				<span class="field-label">Joined</span>
				<span class="field-value">{formatDate(selectedMember.created_at)}</span>
			</div>
		{#if canManageInvites && selectedMember.join_method}
			<div class="profile-field">
				<span class="field-label">Joined via</span>
				<span class="field-value">
					{joinMethodLabel(selectedMember.join_method)}
					{#if selectedMember.join_method === 'invite' && selectedMember.invite_description}
						<span class="join-detail"> — {selectedMember.invite_description}</span>
					{/if}
				</span>
			</div>
		{/if}
		</div>
	{:else}
		<div class="members-header">Members — {onlineCount}/{activeMembers.length}</div>
		<div class="members-list">
			{#each groupedMembers as group}
				<div class="role-group">
					<div class="role-name" style:color={group.role.color || 'var(--text-muted)'}>{group.role.name} — {group.members.length}</div>
					{#each group.members as member}
						<button class="member-item" class:offline={!isUserOnline(member.id)} onclick={() => selectMember(member)}>
							<div class="avatar-wrapper">
								{#if member.avatar_url}
									<img class="member-avatar" src={member.avatar_url} alt="" />
								{:else}
									<span class="member-avatar-placeholder">{(member.display_name || member.username).charAt(0).toUpperCase()}</span>
								{/if}
								<span class="status-dot" class:online={isUserOnline(member.id)}></span>
							</div>
							<span class="member-name">{member.display_name || member.username}</span>
							{#if member.status_text}
								<span class="member-status">{member.status_text}</span>
							{/if}
						</button>
					{/each}
				</div>
			{/each}
		</div>
	{/if}
</aside>

<style>
	.members-panel {
		width: 240px;
		min-width: 240px;
		background: var(--bg-surface);
		border-left: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		height: 100%;
		overflow: hidden;
	}

	.members-header {
		padding: 0.75rem 1rem;
		font-weight: 600;
		font-size: 0.8rem;
		color: var(--text-muted);
		text-transform: uppercase;
		letter-spacing: 0.03em;
		border-bottom: 1px solid var(--border);
	}

	.members-list {
		flex: 1;
		overflow-y: auto;
		padding: 0.25rem 0;
	}

	.role-group {
		margin-bottom: 0.5rem;
	}

	.role-name {
		padding: 0.5rem 1rem 0.15rem;
		font-size: 0.65rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.05em;
	}

	.member-item {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		width: 100%;
		padding: 0.35rem 0.75rem;
		background: none;
		border: none;
		color: var(--text);
		cursor: pointer;
		text-align: left;
		font-size: 0.8rem;
		border-radius: 4px;
		margin: 0 0.5rem;
		width: calc(100% - 1rem);
	}

	.member-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.avatar-wrapper {
		position: relative;
		flex-shrink: 0;
		width: 32px;
		height: 32px;
	}

	.member-avatar {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		object-fit: cover;
	}

	.member-avatar-placeholder {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		background: var(--accent, #5865f2);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.75rem;
		font-weight: 600;
		color: white;
	}

	.status-dot {
		position: absolute;
		bottom: -1px;
		right: -1px;
		width: 10px;
		height: 10px;
		border-radius: 50%;
		border: 2px solid var(--bg-surface);
		background: var(--text-muted);
	}

	.status-dot.online {
		background: #23a55a;
	}

	.member-item.offline {
		opacity: 0.5;
	}

	.member-item.offline:hover {
		opacity: 0.8;
	}

	.member-name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		flex: 1;
	}

	.member-status {
		color: var(--text-muted);
		font-size: 0.65rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		max-width: 60px;
	}

	/* Profile view */
	.profile-view {
		padding: 1rem;
		display: flex;
		flex-direction: column;
		gap: 1rem;
		overflow-y: auto;
	}

	.back-btn {
		background: none;
		border: none;
		color: var(--accent);
		cursor: pointer;
		font-size: 0.75rem;
		padding: 0;
		text-align: left;
		width: fit-content;
	}

	.back-btn:hover {
		text-decoration: underline;
	}

	.profile-header {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.profile-avatar {
		width: 48px;
		height: 48px;
		border-radius: 50%;
		object-fit: cover;
	}

	.profile-avatar-placeholder {
		width: 48px;
		height: 48px;
		border-radius: 50%;
		background: var(--accent);
		color: white;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.2rem;
		font-weight: 600;
	}

	.profile-names {
		display: flex;
		flex-direction: column;
	}

	.profile-display {
		font-weight: 600;
		font-size: 0.9rem;
	}

	.profile-username {
		color: var(--text-muted);
		font-size: 0.75rem;
	}

	.profile-field {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
	}

	.field-label {
		font-size: 0.75rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
	}

	.field-value {
		font-size: 0.85rem;
		color: var(--text);
		word-break: break-word;
	}

	.profile-roles {
		display: flex;
		flex-wrap: wrap;
		gap: 0.3rem;
	}

	.role-tag {
		font-size: 0.7rem;
		font-weight: 600;
		padding: 0.1rem 0.4rem;
		border: 1px solid currentColor;
		border-radius: 4px;
		opacity: 0.8;
	}

	/* Presence in profile detail */
	.profile-presence {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		font-size: 0.8rem;
	}

	.presence-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		flex-shrink: 0;
	}

	.presence-dot.online {
		background: #23a55a;
	}

	.presence-dot.offline {
		background: var(--text-muted);
	}

	.presence-label.online {
		color: #23a55a;
		font-weight: 600;
	}

	.presence-label.offline {
		color: var(--text-muted);
		font-weight: 600;
	}

	.last-seen {
		color: var(--text-muted);
		font-size: 0.75rem;
	}

	.join-detail {
		color: var(--text-muted);
		font-size: 0.8rem;
	}
</style>
