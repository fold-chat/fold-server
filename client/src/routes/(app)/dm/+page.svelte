<script lang="ts">
	import { getDmConversations, getDmUnreadCount, ensureDmLoaded } from '$lib/stores/dm.svelte.js';
	import { getUser, hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { getMembers } from '$lib/stores/members.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { openDm } from '$lib/api/dm.js';
	import { goto } from '$app/navigation';

	// Lazy-load DM data on mount
	ensureDmLoaded();

	let search = $state('');
	let showNewDm = $state(false);
	let userSearch = $state('');

	const canInitiateDm = $derived(hasServerPermission(PermissionName.INITIATE_DM));
	const conversations = $derived(getDmConversations());
	const me = $derived(getUser());

	const filteredConversations = $derived.by(() => {
		if (!search) return conversations;
		const q = search.toLowerCase();
		return conversations.filter((dm) => {
			const other = dm.participants.find((p) => p.id !== me?.id) ?? dm.participants[0];
			return (
				other?.username.toLowerCase().includes(q) ||
				other?.display_name?.toLowerCase().includes(q)
			);
		});
	});

	const filteredMembers = $derived.by(() => {
		if (!userSearch) return [];
		const q = userSearch.toLowerCase();
		return getMembers()
			.filter(
				(m) =>
					m.id !== me?.id &&
					(m.username.toLowerCase().includes(q) ||
						m.display_name?.toLowerCase().includes(q))
			)
			.slice(0, 10);
	});

	async function startDm(userId: string) {
		try {
			const result = await openDm(userId);
			showNewDm = false;
			userSearch = '';
			goto(`/dm/${result.channel_id}`);
		} catch {
			/* ignore */
		}
	}
</script>

<div class="dm-list-page">
	<div class="dm-list-header">
		<h2>Direct Messages</h2>
		{#if canInitiateDm}
			<button class="new-dm-btn" onclick={() => (showNewDm = !showNewDm)}>
				<span class="material-symbols-outlined" style="font-size: 18px">edit</span>
				New Message
			</button>
		{/if}
	</div>

	{#if showNewDm}
		<div class="new-dm-panel">
			<!-- svelte-ignore a11y_autofocus -->
			<input
				class="dm-search-input"
				type="text"
				placeholder="Search members..."
				bind:value={userSearch}
				autofocus
			/>
			{#if filteredMembers.length > 0}
				<div class="user-picker">
					{#each filteredMembers as member}
						<button class="user-pick-item" onclick={() => startDm(member.id)}>
							{#if member.avatar_url}
								<img class="pick-avatar" src={member.avatar_url} alt="" />
							{:else}
								<span class="pick-avatar-placeholder"
									>{(member.display_name || member.username)
										.charAt(0)
										.toUpperCase()}</span
								>
							{/if}
							<span class="pick-name"
								>{member.display_name || member.username}</span
							>
							<span class="pick-username">@{member.username}</span>
						</button>
					{/each}
				</div>
			{:else if userSearch.length > 0}
				<div class="no-results">No members found</div>
			{/if}
		</div>
	{/if}

	<div class="dm-filter">
		<input
			class="dm-search-input"
			type="text"
			placeholder="Search by name..."
			bind:value={search}
		/>
	</div>

	<div class="dm-conversations">
		{#each filteredConversations as dm}
			{@const other = dm.participants.find((p) => p.id !== me?.id) ?? dm.participants[0]}
			{@const unread = getDmUnreadCount(dm.channel_id)}
			<a class="dm-conv-item" class:unread={unread > 0} href="/dm/{dm.channel_id}">
				{#if other?.avatar_url}
					<img class="conv-avatar" src={other.avatar_url} alt="" />
				{:else}
					<span class="conv-avatar-placeholder"
						>{(other?.display_name || other?.username || '?')
							.charAt(0)
							.toUpperCase()}</span
					>
				{/if}
				<div class="conv-info">
					<span class="conv-name"
						>{other?.display_name || other?.username || 'Unknown'}</span
					>
					<span class="conv-time"
						>{new Date(dm.last_activity_at).toLocaleDateString()}</span
					>
				</div>
				{#if unread > 0}
					<span class="conv-unread-badge">{unread > 99 ? '99+' : unread}</span>
				{/if}
				{#if dm.is_blocked}
					<span class="conv-blocked-badge">blocked</span>
				{/if}
			</a>
		{/each}
		{#if filteredConversations.length === 0}
			<div class="dm-empty">
				{search ? 'No conversations match your search' : 'No direct messages yet'}
			</div>
		{/if}
	</div>
</div>

<style>
	.dm-list-page {
		flex: 1;
		display: flex;
		flex-direction: column;
		height: 100%;
		min-width: 0;
	}

	.dm-list-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.75rem 1rem;
		border-bottom: 1px solid var(--border);
		flex-shrink: 0;
	}

	.dm-list-header h2 {
		font-size: 1rem;
		font-weight: 600;
		margin: 0;
	}

	.new-dm-btn {
		display: flex;
		align-items: center;
		gap: 0.3rem;
		padding: 0.4rem 0.75rem;
		font-size: 0.8rem;
		border: none;
		background: var(--accent);
		color: white;
		border-radius: 6px;
		cursor: pointer;
		transition: opacity 0.15s;
	}

	.new-dm-btn:hover {
		opacity: 0.85;
	}

	.new-dm-panel {
		padding: 0.75rem 1rem;
		border-bottom: 1px solid var(--border);
		background: var(--bg-surface);
	}

	.dm-filter {
		padding: 0.5rem 1rem;
		border-bottom: 1px solid var(--border);
		flex-shrink: 0;
	}

	.dm-search-input {
		width: 100%;
		padding: 0.4rem 0.6rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		background: var(--bg);
		color: var(--text);
		font-size: 0.85rem;
		font-family: inherit;
		outline: none;
		box-sizing: border-box;
	}

	.dm-search-input::placeholder {
		color: var(--text-muted);
	}

	.dm-search-input:focus {
		border-color: var(--accent);
	}

	.user-picker {
		display: flex;
		flex-direction: column;
		gap: 2px;
		margin-top: 0.5rem;
	}

	.user-pick-item {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.4rem 0.5rem;
		border: none;
		background: none;
		color: var(--text);
		cursor: pointer;
		border-radius: 6px;
		font-size: 0.85rem;
		text-align: left;
	}

	.user-pick-item:hover {
		background: var(--bg-hover);
	}

	.pick-avatar {
		width: 28px;
		height: 28px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.pick-avatar-placeholder {
		width: 28px;
		height: 28px;
		border-radius: 50%;
		background: var(--bg-active, #3a3d41);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.75rem;
		font-weight: 600;
		color: var(--text-muted);
		flex-shrink: 0;
	}

	.pick-name {
		font-weight: 500;
	}

	.pick-username {
		color: var(--text-muted);
		font-size: 0.75rem;
	}

	.no-results {
		padding: 0.5rem;
		color: var(--text-muted);
		font-size: 0.8rem;
		text-align: center;
	}

	.dm-conversations {
		flex: 1;
		overflow-y: auto;
		padding: 0.5rem;
	}

	.dm-conv-item {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.6rem 0.75rem;
		border-radius: 8px;
		text-decoration: none;
		color: var(--text-muted);
		transition: background 0.12s, color 0.12s;
	}

	.dm-conv-item:hover {
		background: var(--bg-hover);
		color: var(--text);
		text-decoration: none;
	}

	.dm-conv-item.unread {
		color: var(--text);
		font-weight: 600;
	}

	.conv-avatar {
		width: 36px;
		height: 36px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.conv-avatar-placeholder {
		width: 36px;
		height: 36px;
		border-radius: 50%;
		background: var(--bg-active, #3a3d41);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.9rem;
		font-weight: 600;
		color: var(--text-muted);
		flex-shrink: 0;
	}

	.conv-info {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.conv-name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 0.9rem;
	}

	.conv-time {
		font-size: 0.7rem;
		color: var(--text-muted);
		font-weight: 400;
	}

	.conv-unread-badge {
		background: var(--accent);
		color: white;
		font-size: 0.65rem;
		font-weight: 700;
		padding: 0.1rem 0.45rem;
		border-radius: 9999px;
		min-width: 1rem;
		text-align: center;
		flex-shrink: 0;
	}

	.conv-blocked-badge {
		font-size: 0.6rem;
		text-transform: uppercase;
		color: var(--danger, #e74c3c);
		background: color-mix(in srgb, var(--danger, #e74c3c) 15%, transparent);
		padding: 0.05rem 0.3rem;
		border-radius: 3px;
		flex-shrink: 0;
	}

	.dm-empty {
		padding: 2rem;
		text-align: center;
		color: var(--text-muted);
		font-size: 0.85rem;
	}
</style>
