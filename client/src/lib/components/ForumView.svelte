<script lang="ts">
	import { untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { getThreads, createThread } from '$lib/api/threads.js';
	import type { Thread } from '$lib/api/threads.js';
	import { getChannelThreads, setChannelThreads, addChannelThread } from '$lib/stores/threads.svelte.js';
	import { hasChannelPermission, hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { formatTimestamp } from '$lib/utils/markdown.js';
	import MessageCompose from './MessageCompose.svelte';

	let { channelId, channelName, channelTopic = null, channelDescription = null }: { channelId: string; channelName: string; channelTopic?: string | null; channelDescription?: string | null } = $props();

	let threads = $derived(getChannelThreads(channelId));
	let canCreate = $derived(hasChannelPermission(channelId, PermissionName.CREATE_THREADS));
	let canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));

	let loading = $state(false);
	let hasMore = $state(true);
	let showNewPost = $state(false);
	let newTitle = $state('');
	let submitting = $state(false);

	$effect(() => {
		const chId = channelId;
		if (chId) {
			untrack(() => loadThreads(chId));
		}
	});

	async function loadThreads(chId: string) {
		if (getChannelThreads(chId).length > 0) return;
		loading = true;
		try {
			const result = await getThreads(chId, { limit: 25 });
			setChannelThreads(chId, result);
			hasMore = result.length >= 25;
		} catch {
			// handle error
		} finally {
			loading = false;
		}
	}

	async function loadMore() {
		if (!hasMore || loading) return;
		const existing = getChannelThreads(channelId);
		if (existing.length === 0) return;
		const last = existing[existing.length - 1];
		loading = true;
		try {
			const result = await getThreads(channelId, { before: last.last_activity_at, limit: 25 });
			setChannelThreads(channelId, [...existing, ...result]);
			hasMore = result.length >= 25;
		} catch {
			// handle error
		} finally {
			loading = false;
		}
	}

	async function handleNewPost(content: string, attachmentIds?: string[]) {
		const title = newTitle.trim();
		if (!title || !content.trim()) return;
		submitting = true;
		try {
			const thread = await createThread(channelId, { title, content, attachment_ids: attachmentIds });
			addChannelThread(thread);
			showNewPost = false;
			newTitle = '';
			goto(`/channels/${channelId}/threads/${thread.id}`);
		} catch {
			// handle error
		} finally {
			submitting = false;
		}
	}

	function openThread(thread: Thread) {
		goto(`/channels/${channelId}/threads/${thread.id}`);
	}

	function replyLabel(count: number | undefined): string {
		const n = count ?? 0;
		return n === 1 ? '1 reply' : `${n} replies`;
	}
</script>

<div class="forum-view">
	<div class="forum-header">
		<div class="forum-header-info">
			<h2 class="forum-title"># {channelName}</h2>
			{#if channelTopic}
				<span class="header-divider"></span>
				<span class="forum-topic">{channelTopic}</span>
			{/if}
		</div>
		<div class="forum-header-actions">
			{#if canManageChannels}
				<a href="/channels/{channelId}/settings" class="header-link">Permissions</a>
			{/if}
			{#if canCreate}
				<button class="new-post-btn" onclick={() => { showNewPost = !showNewPost; }}>
					{showNewPost ? 'Cancel' : '+ New Post'}
				</button>
			{/if}
		</div>
	</div>
	{#if channelDescription}
		<div class="forum-description">{channelDescription}</div>
	{/if}

	{#if showNewPost}
		<div class="new-post-form">
			<input
				class="new-post-title"
				type="text"
				placeholder="Post title"
				bind:value={newTitle}
				disabled={submitting}
			/>
			<MessageCompose onSend={handleNewPost} disabled={submitting || !newTitle.trim()} />
		</div>
	{/if}

	<div class="thread-list">
		{#if loading && threads.length === 0}
			<div class="loading">Loading threads...</div>
		{/if}

		{#each threads as thread}
			<button class="thread-card" onclick={() => openThread(thread)}>
				<div class="thread-card-header">
					<span class="thread-card-title">{thread.title || 'Untitled'}</span>
					{#if (thread.locked ?? 0) !== 0}
						<span class="thread-lock" title="Locked">🔒</span>
					{/if}
				</div>
				<div class="thread-card-meta">
					<span class="thread-author">{thread.author_display_name || thread.author_username || 'Unknown'}</span>
					<span class="thread-sep">·</span>
					<span class="thread-replies">{replyLabel(thread.reply_count)}</span>
					<span class="thread-sep">·</span>
					<span class="thread-activity">{formatTimestamp(thread.last_activity_at)}</span>
				</div>
			</button>
		{/each}

		{#if hasMore && threads.length > 0}
			<button class="load-more" onclick={loadMore} disabled={loading}>
				{loading ? 'Loading...' : 'Load more'}
			</button>
		{/if}

		{#if !loading && threads.length === 0}
			<div class="empty">No posts yet. {canCreate ? 'Create the first one!' : ''}</div>
		{/if}
	</div>
</div>

<style>
	.forum-view {
		flex: 1;
		display: flex;
		flex-direction: column;
		height: 100vh;
		min-width: 0;
	}

	.forum-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.6rem 1rem;
		border-bottom: 1px solid var(--border);
	}

	.forum-header-info {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-width: 0;
		overflow: hidden;
	}

	.forum-title {
		font-size: 1rem;
		font-weight: 600;
		margin: 0;
		white-space: nowrap;
		flex-shrink: 0;
	}

	.header-divider {
		width: 1px;
		height: 1rem;
		background: var(--border);
		flex-shrink: 0;
	}

	.forum-topic {
		font-size: 0.8rem;
		color: var(--text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.forum-description {
		padding: 0.35rem 1rem;
		font-size: 0.78rem;
		color: var(--text-muted);
		border-bottom: 1px solid var(--border);
		line-height: 1.4;
	}

	.forum-header-actions {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.header-link {
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.header-link:hover {
		color: var(--text);
	}

	.new-post-btn {
		padding: 0.35rem 0.75rem;
		font-size: 0.8rem;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 5px;
		cursor: pointer;
	}

	.new-post-btn:hover {
		opacity: 0.9;
	}

	.new-post-form {
		padding: 0.75rem 1rem;
		border-bottom: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	.new-post-title {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 6px;
		padding: 0.6rem 0.75rem;
		font-size: 0.9rem;
		font-family: inherit;
	}

	.new-post-title:focus {
		outline: none;
		border-color: var(--accent, #5865f2);
	}

	.thread-list {
		flex: 1;
		overflow-y: auto;
		padding: 0.5rem 1rem;
	}

	.thread-card {
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		width: 100%;
		padding: 0.75rem;
		background: none;
		border: 1px solid var(--border);
		border-radius: 6px;
		cursor: pointer;
		text-align: left;
		color: var(--text);
		margin-bottom: 0.5rem;
	}

	.thread-card:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.03));
		border-color: var(--accent, #5865f2);
	}

	.thread-card-header {
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.thread-card-title {
		font-weight: 600;
		font-size: 0.9rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.thread-lock {
		font-size: 0.7rem;
	}

	.thread-card-meta {
		display: flex;
		align-items: center;
		gap: 0.35rem;
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.thread-author {
		font-weight: 500;
	}

	.thread-sep {
		opacity: 0.5;
	}

	.load-more {
		display: block;
		width: 100%;
		padding: 0.5rem;
		background: none;
		border: 1px solid var(--border);
		border-radius: 6px;
		color: var(--text-muted);
		font-size: 0.8rem;
		cursor: pointer;
		text-align: center;
	}

	.load-more:hover:not(:disabled) {
		background: var(--bg-hover, rgba(255, 255, 255, 0.03));
	}

	.load-more:disabled {
		opacity: 0.5;
		cursor: default;
	}

	.loading,
	.empty {
		text-align: center;
		color: var(--text-muted);
		padding: 2rem;
		font-size: 0.85rem;
	}
</style>
