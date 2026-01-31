<script lang="ts">
	import { getNotifications } from '$lib/stores/notifications.svelte.js';
	import { goto } from '$app/navigation';

	function handleClick(channelId: string, messageId: string) {
		goto(`/channels/${channelId}?around=${messageId}`);
	}

	function relativeTime(ts: number): string {
		const diff = Math.floor((Date.now() - ts) / 1000);
		if (diff < 60) return 'just now';
		if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
		if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
		return `${Math.floor(diff / 86400)}d ago`;
	}
</script>

<aside class="notif-panel">
	<div class="notif-header">
		<span class="notif-title">Notifications</span>
	</div>
	<div class="notif-list">
		{#each getNotifications() as item}
			<button class="notif-item" onclick={() => handleClick(item.message.channel_id, item.message.id)}>
				<div class="notif-meta">
					<span class="notif-author">{item.message.author_display_name || item.message.author_username || 'Unknown'}</span>
					<span class="notif-channel">#{item.channelName}</span>
					<span class="notif-time">{relativeTime(item.timestamp)}</span>
				</div>
				<div class="notif-preview">{item.message.content?.slice(0, 200) || '(attachment)'}</div>
			</button>
		{:else}
			<div class="notif-empty">No notifications yet. Mentions will appear here.</div>
		{/each}
	</div>
</aside>

<style>
	.notif-panel {
		width: 280px;
		min-width: 280px;
		background: var(--bg-surface);
		border-left: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		height: 100%;
		overflow: hidden;
	}

	.notif-header {
		padding: 0.75rem 1rem;
		border-bottom: 1px solid var(--border);
	}

	.notif-title {
		font-weight: 600;
		font-size: 0.8rem;
		color: var(--text-muted);
		text-transform: uppercase;
		letter-spacing: 0.03em;
	}

	.notif-list {
		flex: 1;
		overflow-y: auto;
		padding: 0.25rem 0;
	}

	.notif-item {
		width: 100%;
		padding: 0.6rem 1rem;
		background: none;
		border: none;
		color: var(--text);
		cursor: pointer;
		text-align: left;
		border-bottom: 1px solid var(--border);
		font-size: 0.8rem;
	}

	.notif-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.notif-meta {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		margin-bottom: 0.2rem;
	}

	.notif-author {
		font-weight: 600;
		font-size: 0.78rem;
	}

	.notif-channel {
		color: var(--text-muted);
		font-size: 0.7rem;
	}

	.notif-time {
		color: var(--text-muted);
		font-size: 0.65rem;
		margin-left: auto;
	}

	.notif-preview {
		color: var(--text-muted);
		font-size: 0.75rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.notif-empty {
		padding: 2rem 1rem;
		text-align: center;
		color: var(--text-muted);
		font-size: 0.8rem;
	}
</style>
