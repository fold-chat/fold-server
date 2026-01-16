<script lang="ts">
	import { getChannelsByCategory, getUnreadCount } from '$lib/stores/channels.svelte.js';
	import { getActiveChannelId } from '$lib/stores/messages.svelte.js';
	import { getConnectionState } from '$lib/stores/ws.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { goto } from '$app/navigation';

	let { onCreateChannel, onCreateCategory }: { onCreateChannel?: () => void; onCreateCategory?: () => void } = $props();

	const canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));
	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));

	function selectChannel(id: string) {
		goto(`/channels/${id}`);
	}
</script>

<aside class="sidebar">
	{#if getConnectionState() !== 'connected'}
		<div class="connection-banner" class:reconnecting={getConnectionState() === 'reconnecting'}>
			{getConnectionState() === 'reconnecting' ? 'Reconnecting...' : 'Disconnected'}
		</div>
	{/if}

	<div class="sidebar-header">
		<h2>Fray</h2>
	</div>

	<nav class="channel-list">
		{#each getChannelsByCategory() as group}
			{#if group.category}
				<div class="category-header">
					<span class="category-name">{group.category.name}</span>
				</div>
			{/if}
			{#each group.channels as channel}
				{@const unread = getUnreadCount(channel.id)}
				<button
					class="channel-item"
					class:active={getActiveChannelId() === channel.id}
					class:unread={unread > 0}
					onclick={() => selectChannel(channel.id)}
				>
					<span class="channel-hash">#</span>
					<span class="channel-name">{channel.name}</span>
					{#if unread > 0}
						<span class="unread-badge">{unread}</span>
					{/if}
				</button>
			{/each}
		{/each}
	</nav>

	<div class="sidebar-footer">
		{#if onCreateChannel && canManageChannels}
			<button class="sidebar-action" onclick={onCreateChannel}>+ Channel</button>
		{/if}
		{#if onCreateCategory && canManageChannels}
			<button class="sidebar-action" onclick={onCreateCategory}>+ Category</button>
		{/if}
		{#if canManageRoles}
			<button class="sidebar-action" onclick={() => goto('/settings/roles')}>Roles</button>
			<button class="sidebar-action" onclick={() => goto('/settings/members')}>Members</button>
		{/if}
	</div>
</aside>

<style>
	.sidebar {
		width: 240px;
		min-width: 240px;
		background: var(--bg-surface);
		border-right: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		height: 100vh;
	}

	.connection-banner {
		padding: 0.4rem 0.75rem;
		font-size: 0.75rem;
		text-align: center;
		background: var(--danger, #e74c3c);
		color: white;
	}

	.connection-banner.reconnecting {
		background: var(--warning, #f39c12);
	}

	.sidebar-header {
		padding: 1rem;
		border-bottom: 1px solid var(--border);
	}

	.sidebar-header h2 {
		font-size: 1.1rem;
		margin: 0;
	}

	.channel-list {
		flex: 1;
		overflow-y: auto;
		padding: 0.5rem 0;
	}

	.category-header {
		padding: 0.5rem 1rem 0.25rem;
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.category-name {
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
	}

	.channel-item {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		width: 100%;
		padding: 0.35rem 1rem;
		border: none;
		background: none;
		color: var(--text-muted);
		font-size: 0.875rem;
		cursor: pointer;
		text-align: left;
		border-radius: 4px;
		margin: 0 0.5rem;
		width: calc(100% - 1rem);
	}

	.channel-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	.channel-item.active {
		background: var(--bg-active, rgba(255, 255, 255, 0.1));
		color: var(--text);
	}

	.channel-item.unread {
		color: var(--text);
		font-weight: 600;
	}

	.channel-hash {
		opacity: 0.5;
		font-weight: 500;
	}

	.channel-name {
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.unread-badge {
		background: var(--accent, #5865f2);
		color: white;
		font-size: 0.65rem;
		font-weight: 700;
		padding: 0.1rem 0.4rem;
		border-radius: 8px;
		min-width: 1rem;
		text-align: center;
	}

	.sidebar-footer {
		padding: 0.5rem;
		border-top: 1px solid var(--border);
		display: flex;
		gap: 0.5rem;
	}

	.sidebar-action {
		flex: 1;
		padding: 0.4rem;
		font-size: 0.75rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 4px;
		cursor: pointer;
	}

	.sidebar-action:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}
</style>
