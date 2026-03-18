<script lang="ts">
	import { untrack } from 'svelte';
	import { goto } from '$app/navigation';
	import { getThreads } from '$lib/api/threads.js';
	import type { Thread } from '$lib/api/threads.js';
	import { getChannelThreads, setChannelThreads } from '$lib/stores/threads.svelte.js';
	import { hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { getChannelById, getCategories } from '$lib/stores/channels.svelte.js';
import { contentPreview } from '$lib/utils/markdown.js';
	import { getMemberRoleColor } from '$lib/stores/members.svelte.js';

	let { channelId, channelName, channelTopic = null, channelDescription = null }: { channelId: string; channelName: string; channelTopic?: string | null; channelDescription?: string | null } = $props();

	let threads = $derived(getChannelThreads(channelId));
	let channel = $derived(getChannelById(channelId));
	let isArchived = $derived(!!channel?.archived_at);
	let canCreate = $derived(!isArchived && hasChannelPermission(channelId, PermissionName.CREATE_THREADS));
	let categoryName = $derived.by(() => {
		const ch = getChannelById(channelId);
		if (!ch?.category_id) return null;
		return getCategories().find(c => c.id === ch.category_id)?.name ?? null;
	});

	let loading = $state(false);
	let hasMore = $state(false);

	$effect(() => {
		const chId = channelId;
		if (chId) {
			hasMore = false;
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

	function openThread(thread: Thread) {
		goto(`/channels/${channelId}/threads/${thread.id}`);
	}

	function timeAgo(dateStr: string): string {
		const date = new Date(dateStr.endsWith('Z') ? dateStr : dateStr + 'Z');
		const diff = Math.floor((Date.now() - date.getTime()) / 1000);
		if (diff < 60) return 'just now';
		if (diff < 3600) return `${Math.floor(diff / 60)}m ago`;
		if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
		if (diff < 604800) return `${Math.floor(diff / 86400)}d ago`;
		return date.toLocaleDateString([], { month: 'short', day: 'numeric' });
	}

	function replyLabel(count: number | undefined): string {
		const n = count ?? 0;
		return n === 1 ? '1 reply' : `${n} replies`;
	}
</script>

<div class="forum-view">
	<!-- Header -->
	<div class="forum-header">
		<div class="forum-header-left">
			{#if categoryName}
				<span class="breadcrumb-cat">{categoryName}</span>
				<span class="breadcrumb-sep">›</span>
			{/if}
			<h2 class="forum-title"># {channelName}</h2>
			{#if channelTopic}
				<span class="header-divider"></span>
				<span class="forum-topic">{channelTopic}</span>
			{/if}
		</div>
		{#if canCreate}
			<button class="new-thread-btn" onclick={() => goto(`/channels/${channelId}/threads/new`)}>
				+ New Thread
			</button>
		{/if}
	</div>

	{#if isArchived}
		<div class="archived-banner">This channel is archived — no new threads can be created.</div>
	{/if}

	{#if channelDescription}
		<div class="forum-description">{channelDescription}</div>
	{/if}

	<!-- Thread list -->
	<div class="thread-list">
		{#if loading && threads.length === 0}
			<div class="loading">Loading threads…</div>
		{/if}

		{#each threads as thread}
			<button class="thread-card" class:pinned={thread.pinned === 1} onclick={() => openThread(thread)}>
				{#if thread.pinned === 1}
					<div class="pinned-badge">📌 Pinned</div>
				{/if}
				<div class="card-author">
					{#if thread.author_avatar_url}
						<img class="avatar avatar-sm" src={thread.author_avatar_url} alt="" />
					{:else}
						<div class="avatar avatar-sm avatar-fallback">{(thread.author_display_name || thread.author_username || '?')[0].toUpperCase()}</div>
					{/if}
					<span class="author-name" style:color={getMemberRoleColor(thread.author_id)}>{thread.author_display_name || thread.author_username || 'Unknown'}</span>
					<span class="card-dot">·</span>
					<span class="card-time">{timeAgo(thread.created_at)}</span>
				</div>
				<div class="card-title-row">
					<span class="card-title">{thread.title || 'Untitled'}</span>
					{#if thread.locked}
						<span class="lock-badge" title="Locked">🔒</span>
					{/if}
				</div>
				{#if thread.first_message_content}
					<p class="card-preview">{contentPreview(thread.first_message_content)}</p>
				{/if}
				<div class="card-footer">
					<span class="reply-count">💬 {replyLabel(thread.reply_count)}</span>
					{#if thread.last_reply_at}
						<span class="card-dot">·</span>
						<span class="last-reply">
							{#if thread.last_reply_avatar_url}
								<img class="avatar avatar-xs" src={thread.last_reply_avatar_url} alt="" />
							{/if}
							{#if thread.last_reply_username}
								<span class="last-reply-name">{thread.last_reply_username}</span>
							{/if}
							replied {timeAgo(thread.last_reply_at)}
						</span>
					{/if}
				</div>
			</button>
		{/each}

		{#if hasMore && threads.length > 0}
			<button class="load-more" onclick={loadMore} disabled={loading}>
				{loading ? 'Loading…' : 'Load more'}
			</button>
		{/if}

		{#if !loading && threads.length === 0}
			<div class="empty-state">
				<div class="empty-icon">💬</div>
				<p class="empty-title">No threads yet</p>
				{#if canCreate}
					<p class="empty-sub">Start a conversation by creating the first thread.</p>
				{/if}
			</div>
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

	/* ── Header ── */
	.forum-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.5rem 1rem;
		border-bottom: 1px solid var(--border);
		min-height: 44px;
		gap: 1rem;
		flex-shrink: 0;
	}

	.forum-header-left {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-width: 0;
		overflow: hidden;
	}

	.breadcrumb-cat {
		font-size: 0.8rem;
		color: var(--text-muted);
		white-space: nowrap;
	}

	.breadcrumb-sep {
		font-size: 0.8rem;
		color: var(--text-muted);
		opacity: 0.5;
	}

	.forum-title {
		font-size: 0.9rem;
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

	.archived-banner {
		padding: 0.4rem 1rem;
		font-size: 0.78rem;
		color: var(--text-muted);
		background: color-mix(in srgb, var(--text-muted) 8%, transparent);
		border-bottom: 1px solid var(--border);
		text-align: center;
	}

	.new-thread-btn {
		padding: 0.4rem 1rem;
		font-size: 0.8rem;
		font-weight: 500;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 6px;
		cursor: pointer;
		white-space: nowrap;
		flex-shrink: 0;
	}

	.new-thread-btn:hover {
		opacity: 0.9;
	}

	/* ── Thread list ── */
	.thread-list {
		flex: 1;
		overflow-y: auto;
		padding: 0.75rem 1rem;
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	/* ── Thread card ── */
	.thread-card {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
		width: 100%;
		padding: 0.75rem 1rem;
		background: var(--bg-surface, rgba(255, 255, 255, 0.02));
		border: 1px solid var(--border);
		border-radius: 8px;
		cursor: pointer;
		text-align: left;
		color: var(--text);
		transition: border-color 0.15s, background 0.15s;
	}

	.thread-card:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.04));
		border-color: var(--accent, #5865f2);
	}

	.thread-card.pinned {
		border-left: 3px solid var(--accent, #5865f2);
		background: color-mix(in srgb, var(--accent, #5865f2) 5%, var(--bg-surface, rgba(255, 255, 255, 0.02)));
	}

	.pinned-badge {
		font-size: 0.7rem;
		font-weight: 600;
		color: var(--accent, #5865f2);
		text-transform: uppercase;
		letter-spacing: 0.03em;
		margin-bottom: 0.1rem;
	}

	/* Author row */
	.card-author {
		display: flex;
		align-items: center;
		gap: 0.35rem;
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.avatar {
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.avatar-sm {
		width: 20px;
		height: 20px;
	}

	.avatar-xs {
		width: 16px;
		height: 16px;
	}

	.avatar-fallback {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--accent, #5865f2);
		color: white;
		font-size: 0.6rem;
		font-weight: 600;
	}

	.author-name {
		font-weight: 500;
		color: var(--text);
	}

	.card-dot {
		opacity: 0.4;
	}

	/* Title row */
	.card-title-row {
		display: flex;
		align-items: center;
		gap: 0.4rem;
	}

	.card-title {
		font-weight: 600;
		font-size: 0.95rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.thread-card:hover .card-title {
		color: var(--accent, #5865f2);
	}

	.lock-badge {
		font-size: 0.7rem;
		flex-shrink: 0;
	}

	/* Content preview */
	.card-preview {
		margin: 0;
		font-size: 0.8rem;
		color: var(--text-muted);
		line-height: 1.45;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
		overflow: hidden;
	}

	/* Footer */
	.card-footer {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		font-size: 0.72rem;
		color: var(--text-muted);
		margin-top: 0.15rem;
	}

	.last-reply {
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}

	.last-reply-name {
		font-weight: 500;
	}

	/* Load more */
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

	/* Empty state */
	.loading {
		text-align: center;
		color: var(--text-muted);
		padding: 2rem;
		font-size: 0.85rem;
	}

	.empty-state {
		text-align: center;
		padding: 3rem 1rem;
		color: var(--text-muted);
	}

	.empty-icon {
		font-size: 2.5rem;
		margin-bottom: 0.5rem;
	}

	.empty-title {
		font-size: 1rem;
		font-weight: 600;
		color: var(--text);
		margin: 0 0 0.25rem;
	}

	.empty-sub {
		font-size: 0.85rem;
		margin: 0;
	}
</style>
