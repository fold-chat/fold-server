<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { untrack } from 'svelte';
import { getThread, getThreadMessages as fetchThreadMessages, replyToThread, updateThreadReadState, updateThread, deleteThread as apiDeleteThread } from '$lib/api/threads.js';
import { editMessage, deleteMessage, type Message } from '$lib/api/messages.js';
	import {
		getActiveThread, setActiveThread,
		getThreadMessages, setThreadMessages, prependThreadMessages,
		setThreadLoading, setThreadHasMore, hasMoreThreadMessages, isThreadLoading,
		getThreadTypingUsers, markThreadRead
	} from '$lib/stores/threads.svelte.js';
	import { getChannelById, getCategories } from '$lib/stores/channels.svelte.js';
	import { getUser, hasChannelPermission } from '$lib/stores/auth.svelte.js';
import { PermissionName } from '$lib/permissions.js';
import { renderMarkdown, contentPreview, isEmojiOnly, extractYouTubeVideoIds } from '$lib/utils/markdown.js';
	import { getYoutubeEmbedEnabled } from '$lib/stores/auth.svelte.js';
	import YouTubeEmbed from '$lib/components/YouTubeEmbed.svelte';
import { send } from '$lib/stores/ws.svelte.js';
import { getMemberRoleColor } from '$lib/stores/members.svelte.js';
	import { openMemberProfile } from '$lib/stores/membersPanel.svelte.js';
	import MessageList from '$lib/components/MessageList.svelte';
	import MessageCompose from '$lib/components/MessageCompose.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';

	let channelId = $derived(page.params.id!);
	let threadId = $derived(page.params.threadId!);
	let highlightParam = $derived(page.url.searchParams.get('highlight'));
	let channel = $derived(getChannelById(channelId));
	let categoryName = $derived.by(() => {
		if (!channel?.category_id) return null;
		return getCategories().find(c => c.id === channel!.category_id)?.name ?? null;
	});

	let thread = $state<Awaited<ReturnType<typeof getThread>> | null>(null);
	let loadError = $state(false);
	let highlightMessageId = $state<string | null>(null);

	let messages = $derived(getThreadMessages(threadId));
	let loading = $derived(isThreadLoading(threadId));
	let canLoadMore = $derived(hasMoreThreadMessages(threadId));
	let originalPost = $derived(!canLoadMore && messages.length > 0 ? messages[0] : null);
	let replies = $derived(originalPost ? messages.slice(1) : messages);
	let replyCountLabel = $derived.by(() => {
		const n = thread?.reply_count ?? Math.max(0, messages.length - 1);
		return n === 1 ? '1 reply' : `${n} replies`;
	});
	let typingUsers = $derived(getThreadTypingUsers(threadId));
	let isLocked = $derived((thread?.locked ?? 0) !== 0);
	let canSend = $derived(
		channelId && !isLocked
			? hasChannelPermission(channelId, PermissionName.SEND_MESSAGES)
			: isLocked
				? (hasChannelPermission(channelId, PermissionName.SEND_IN_LOCKED_THREADS) || hasChannelPermission(channelId, PermissionName.MANAGE_THREADS))
				: false
	);
	let canUploadFiles = $derived(channelId ? hasChannelPermission(channelId, PermissionName.UPLOAD_FILES) : false);
	let canManageMessages = $derived(channelId ? hasChannelPermission(channelId, PermissionName.MANAGE_MESSAGES) : false);
	let canManageOwnMessages = $derived(channelId ? hasChannelPermission(channelId, PermissionName.MANAGE_OWN_MESSAGES) : false);
	let canAddReactions = $derived(channelId ? hasChannelPermission(channelId, PermissionName.ADD_REACTIONS) : false);
	let currentUserId = $derived(getUser()?.id ?? '');

	// Thread moderation permissions
	let canManageThread = $derived(channelId ? hasChannelPermission(channelId, PermissionName.MANAGE_THREADS) : false);
	let canManageOwnThread = $derived(channelId && thread ? (hasChannelPermission(channelId, PermissionName.MANAGE_OWN_THREADS) && thread.author_id === currentUserId) : false);
	let canDeleteThread = $derived(canManageThread || canManageOwnThread);
	let canPin = $derived(canManageThread);
	let canLock = $derived(canManageThread);

	let editingId = $state<string | null>(null);
	let editContent = $state('');
	let typingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let stopTypingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let deleteConfirmOpen = $state(false);
	let pendingDeleteId = $state<string | null>(null);
	let deleteThreadConfirmOpen = $state(false);
	let lockConfirmOpen = $state(false);
	let pendingInsert = $state<string | null>(null);
	let threadTopAnchor = $state<HTMLDivElement | null>(null);

	function scrollToTop() {
		threadTopAnchor?.scrollIntoView({ behavior: 'smooth' });
	}

	$effect(() => {
		const tId = threadId;
		const hl = highlightParam;
		if (tId) {
			untrack(() => loadThread(tId, hl));
		}
	});

	async function loadThread(tId: string, highlight: string | null) {
		loadError = false;
		highlightMessageId = highlight;
		try {
			thread = await getThread(tId);
			setActiveThread(thread);
		} catch {
			loadError = true;
			return;
		}
		if (getThreadMessages(tId).length > 0 && !highlight) {
			doMarkRead(tId);
			return;
		}
		setThreadLoading(tId, true);
		try {
			const msgs = await fetchThreadMessages(tId, { limit: 50 });
			setThreadMessages(tId, msgs);
			setThreadHasMore(tId, msgs.length >= 50);
			doMarkRead(tId);
		} catch {
			// handle error
		} finally {
			setThreadLoading(tId, false);
		}
	}

	async function loadOlder() {
		const msgs = getThreadMessages(threadId);
		if (msgs.length === 0 || !hasMoreThreadMessages(threadId)) return;
		setThreadLoading(threadId, true);
		try {
			const older = await fetchThreadMessages(threadId, { before: msgs[0].id, limit: 50 });
			prependThreadMessages(threadId, older);
			setThreadHasMore(threadId, older.length >= 50);
		} catch {
			// handle error
		} finally {
			setThreadLoading(threadId, false);
		}
	}

	async function handleSend(content: string, attachmentIds?: string[]) {
		stopTyping();
		try {
			await replyToThread(threadId, content, attachmentIds);
			doMarkRead(threadId);
		} catch {
			// handle error
		}
	}

	function handleTyping() {
		if (stopTypingTimeout) clearTimeout(stopTypingTimeout);
		stopTypingTimeout = setTimeout(() => {
			stopTypingTimeout = null;
			stopTyping();
		}, 5000);
		if (typingTimeout) return;
		send('TYPING', { thread_id: threadId });
		typingTimeout = setTimeout(() => { typingTimeout = null; }, 3000);
	}

	function stopTyping() {
		if (typingTimeout) { clearTimeout(typingTimeout); typingTimeout = null; }
		if (stopTypingTimeout) { clearTimeout(stopTypingTimeout); stopTypingTimeout = null; }
		send('TYPING_STOP', { thread_id: threadId });
	}

	async function handleEdit(id: string, content: string) {
		try {
			await editMessage(id, content);
			editingId = null;
		} catch { /* */ }
	}

	function handleDelete(id: string) {
		pendingDeleteId = id;
		deleteConfirmOpen = true;
	}

	async function confirmDelete() {
		deleteConfirmOpen = false;
		if (!pendingDeleteId) return;
		try { await deleteMessage(pendingDeleteId); } catch { /* */ }
		pendingDeleteId = null;
	}

	function cancelDelete() {
		deleteConfirmOpen = false;
		pendingDeleteId = null;
	}

	function startEdit(id: string, content: string) {
		editingId = id;
		editContent = content;
	}

	function cancelEdit() {
		editingId = null;
		editContent = '';
	}

	function doMarkRead(tId: string) {
		const msgs = getThreadMessages(tId);
		if (msgs.length > 0) {
			const last = msgs[msgs.length - 1];
			markThreadRead(tId, last.id);
			updateThreadReadState(tId, last.id).catch(() => {});
		}
	}

	// Thread moderation actions
	async function handlePin() {
		if (!thread) return;
		try {
			thread = await updateThread(threadId, { pinned: thread.pinned ? 0 : 1 });
		} catch { /* */ }
	}

	function handleLockClick() {
		if (!thread) return;
		if (thread.locked) {
			// Unlock immediately, no confirm
			doLockToggle();
		} else {
			lockConfirmOpen = true;
		}
	}

	async function doLockToggle() {
		if (!thread) return;
		try {
			thread = await updateThread(threadId, { locked: thread.locked ? 0 : 1 });
		} catch { /* */ }
	}

	function confirmLock() {
		lockConfirmOpen = false;
		doLockToggle();
	}

	function handleDeleteThreadClick() {
		deleteThreadConfirmOpen = true;
	}

	async function confirmDeleteThread() {
		deleteThreadConfirmOpen = false;
		try {
			await apiDeleteThread(threadId);
			setActiveThread(null);
			goto(`/channels/${channelId}`);
		} catch { /* */ }
	}

	function goBack() {
		setActiveThread(null);
		goto(`/channels/${channelId}`);
	}

	function handleQuoteReply(msg: Message) {
		const preview = contentPreview(msg.content, 100);
		const author = msg.author_display_name || msg.author_username || 'Unknown';
		pendingInsert = `> ${preview}\n> — @${author} [↩](#msg-${msg.id})\n\n`;
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

	// Phase 3: sync local thread from WS-driven activeThread updates
	$effect(() => {
		const active = getActiveThread();
		if (active && active.id === threadId) {
			thread = active;
		} else if (thread && !active) {
			// Thread was deleted via WS while viewing
			goto(`/channels/${channelId}`);
		}
	});
</script>

<div class="thread-detail">
	<div class="thread-detail-header">
		<nav class="breadcrumb">
			{#if categoryName}
				<span class="bc-muted">{categoryName}</span>
				<span class="bc-sep">›</span>
			{/if}
			{#if channel}
				<button class="bc-link" onclick={goBack}># {channel.name}</button>
			{/if}
		</nav>
	</div>

	{#snippet threadHeaderContent()}
		<div class="thread-top-anchor" bind:this={threadTopAnchor}></div>
		{#if thread}
			<div class="thread-sticky-header">
				<div class="op-author">
					{#if originalPost?.author_avatar_url || thread.author_avatar_url}
						<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
						<img class="op-avatar clickable" src={originalPost?.author_avatar_url || thread.author_avatar_url} alt="" onclick={() => openMemberProfile(thread!.author_id)} />
					{:else}
						<!-- svelte-ignore a11y_no_static_element_interactions -->
						<div class="op-avatar op-avatar-fallback clickable" onclick={() => openMemberProfile(thread!.author_id)}>{(thread.author_display_name || thread.author_username || '?')[0].toUpperCase()}</div>
					{/if}
					<div class="op-author-info">
						<button class="op-name" style:color={getMemberRoleColor(thread.author_id)} onclick={() => openMemberProfile(thread!.author_id)}>{thread.author_display_name || thread.author_username || 'Unknown'}</button>
						<span class="op-time">{timeAgo(thread.created_at)}</span>
					</div>
					{#if thread.pinned || isLocked}
						<div class="thread-badges-inline">
							{#if thread.pinned}<span class="badge badge-pin">📌</span>{/if}
							{#if isLocked}<span class="badge badge-lock">🔒</span>{/if}
						</div>
					{/if}
				</div>
				<div class="sticky-title-row">
					<h1 class="thread-title">{thread.title || 'Thread'}</h1>
					<button class="jump-top-btn" onclick={scrollToTop} title="Jump to top">
						<svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polyline points="18 15 12 9 6 15"/></svg>
					</button>
				</div>
			</div>
			<div class="thread-op-body">
				{#if originalPost}
					<div class="op-content" class:emoji-only={isEmojiOnly(originalPost.content)}>{@html renderMarkdown(originalPost.content, { mentions: originalPost.mentions, mention_roles: originalPost.mention_roles, mention_everyone: originalPost.mention_everyone })}</div>
					{#if getYoutubeEmbedEnabled()}
						{#each extractYouTubeVideoIds(originalPost.content) as videoId}
							<YouTubeEmbed {videoId} />
						{/each}
					{/if}
					{#if originalPost.attachments?.length}
						<div class="op-attachments">
							{#each originalPost.attachments as att}
							{#if att.mime_type.startsWith('image/')}
									<div class="op-attachment-wrapper">
										<img class="op-attachment-img" src={att.url} alt={att.original_name} onerror={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; (e.currentTarget as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }} />
										<div class="image-broken hidden">
											<span class="image-broken-icon">🖼️</span>
											<span class="image-broken-text">{att.original_name ?? 'Image'} could not be loaded</span>
										</div>
									</div>
								{:else}
									<a class="op-attachment-file" href={att.url} download={att.original_name}>
										📄 {att.original_name}
									</a>
								{/if}
							{/each}
						</div>
					{/if}
				{:else if thread.first_message_content}
					<p class="op-preview">{contentPreview(thread.first_message_content)}</p>
				{/if}
				{#if canPin || canLock || canDeleteThread}
					<div class="thread-actions">
						{#if canPin}
							<button class="action-btn" onclick={handlePin} title={thread.pinned ? 'Unpin thread' : 'Pin thread'}>
								📌 {thread.pinned ? 'Unpin' : 'Pin'}
							</button>
						{/if}
						{#if canLock}
							<button class="action-btn" onclick={handleLockClick} title={isLocked ? 'Unlock thread' : 'Lock thread'}>
								{isLocked ? '🔓 Unlock' : '🔒 Lock'}
							</button>
						{/if}
						{#if canDeleteThread}
							<button class="action-btn action-btn-danger" onclick={handleDeleteThreadClick} title="Delete thread">
								🗑️ Delete
							</button>
						{/if}
					</div>
				{/if}
			</div>
		{/if}
		{#if replies.length > 0}
			<div class="replies-header">{replyCountLabel}</div>
		{/if}
	{/snippet}

	{#if loadError}
		<div class="error-state">Thread not found or could not be loaded.</div>
	{:else}
		<MessageList
			messages={replies}
			{loading}
			{canLoadMore}
			{currentUserId}
			{editingId}
			{editContent}
			{typingUsers}
			{canManageMessages}
			{canManageOwnMessages}
			{canAddReactions}
			{highlightMessageId}
			threadMode
			headerContent={threadHeaderContent}
			onLoadMore={loadOlder}
			onStartEdit={startEdit}
			onCancelEdit={cancelEdit}
			onSaveEdit={handleEdit}
			onDelete={handleDelete}
			onQuoteReply={canSend ? handleQuoteReply : undefined}
		/>

		{#if isLocked && !canSend}
			<div class="locked-placeholder">🔒 This thread is locked</div>
		{:else}
			<MessageCompose onSend={handleSend} onTyping={handleTyping} disabled={!canSend} {canUploadFiles} forumMode {pendingInsert} onInsertConsumed={() => pendingInsert = null} />
		{/if}
	{/if}
</div>

<ConfirmDialog
	open={deleteConfirmOpen}
	title="Delete Message"
	message="Are you sure you want to delete this message? This cannot be undone."
	onconfirm={confirmDelete}
	oncancel={cancelDelete}
/>

<ConfirmDialog
	open={deleteThreadConfirmOpen}
	title="Delete Thread"
	message="Are you sure you want to delete this thread and all its messages? This cannot be undone."
	confirmLabel="Delete Thread"
	onconfirm={confirmDeleteThread}
	oncancel={() => deleteThreadConfirmOpen = false}
/>

<ConfirmDialog
	open={lockConfirmOpen}
	title="Lock Thread"
	message="Locking this thread will prevent members from posting new replies. Users with Manage Threads or Send in Locked Threads permission can still reply."
	confirmLabel="Lock"
	danger={false}
	onconfirm={confirmLock}
	oncancel={() => lockConfirmOpen = false}
/>

<style>
	.thread-detail {
		flex: 1;
		display: flex;
		flex-direction: column;
		height: 100vh;
		min-width: 0;
	}

	.thread-detail-header {
		display: flex;
		align-items: center;
		padding: 0.5rem 1.5rem;
		border-bottom: 1px solid var(--border);
		min-height: 44px;
		flex-shrink: 0;
	}

	.breadcrumb {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-width: 0;
		overflow: hidden;
	}

	.bc-muted {
		font-size: 0.8rem;
		color: var(--text-muted);
		white-space: nowrap;
	}

	.bc-sep {
		font-size: 0.8rem;
		color: var(--text-muted);
		opacity: 0.5;
	}

	.bc-link {
		background: none;
		border: none;
		padding: 0;
		font-size: 0.8rem;
		color: var(--text-muted);
		cursor: pointer;
		white-space: nowrap;
	}

	.bc-link:hover {
		color: var(--accent, #5865f2);
	}

	/* ── Sticky thread header ── */
	.thread-sticky-header {
		position: sticky;
		top: 0;
		z-index: 10;
		background: color-mix(in srgb, var(--bg) 88%, transparent);
		backdrop-filter: blur(12px);
		-webkit-backdrop-filter: blur(12px);
		padding: 0.75rem 1.5rem;
		border-bottom: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
		box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
	}

	.op-author {
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}

	.op-avatar {
		width: 36px;
		height: 36px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.clickable {
		cursor: pointer;
	}

	.clickable:hover {
		opacity: 0.8;
	}

	.op-avatar-fallback {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--accent, #5865f2);
		color: white;
		font-size: 0.85rem;
		font-weight: 600;
	}

	.op-author-info {
		display: flex;
		flex-direction: column;
	}

	.op-name {
		font-weight: 600;
		font-size: 0.85rem;
		background: none;
		border: none;
		padding: 0;
		cursor: pointer;
		font-family: inherit;
		text-align: left;
	}

	.op-name:hover {
		text-decoration: underline;
		background: none;
	}

	.op-time {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.thread-badges-inline {
		display: flex;
		gap: 0.3rem;
		margin-left: auto;
	}

	.sticky-title-row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}

	.thread-title {
		font-size: 1.15rem;
		font-weight: 700;
		margin: 0;
		line-height: 1.3;
		flex: 1;
		min-width: 0;
	}

	.jump-top-btn {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text-muted);
		border-radius: 6px;
		padding: 0.3rem;
		cursor: pointer;
		display: flex;
		align-items: center;
		justify-content: center;
		flex-shrink: 0;
		transition: color 0.15s ease, background 0.15s ease;
	}

	.jump-top-btn:hover {
		color: var(--text);
		background: var(--bg-hover);
	}

	/* ── Scrollable OP body ── */
	.thread-op-body {
		padding: 1rem 1.5rem 1.25rem;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		border-bottom: 1px solid var(--border);
	}

	.op-content {
		font-size: 0.9rem;
		line-height: 1.55;
		color: var(--text);
	}

	.op-content :global(p) {
		margin: 0 0 0.4rem;
	}

	.op-content :global(p:last-child) {
		margin-bottom: 0;
	}

	.op-content :global(code) {
		background: var(--bg-surface);
		padding: 0.1rem 0.3rem;
		border-radius: 3px;
		font-size: 0.85em;
	}

	.op-content :global(pre) {
		background: var(--bg-surface);
		padding: 0.75rem;
		border-radius: 6px;
		overflow-x: auto;
		margin: 0.25rem 0;
	}

	.op-content :global(blockquote) {
		border-left: 3px solid var(--border);
		margin: 0.25rem 0;
		padding-left: 0.75rem;
		color: var(--text-muted);
	}

	.op-content :global(a) {
		color: var(--accent, #5865f2);
	}

	.op-content :global(.custom-emoji) {
		width: 1.75em;
		height: 1.75em;
		object-fit: contain;
		vertical-align: middle;
		margin: 0 0.05em;
	}

	.op-content :global(.unicode-emoji) {
		font-size: 1.4em;
		line-height: 1;
		vertical-align: middle;
	}

	.op-content.emoji-only {
		font-size: 2.5rem;
		line-height: 1.2;
	}

	.op-attachments {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
	}

	.op-attachment-img {
		max-width: 400px;
		max-height: 300px;
		border-radius: 8px;
		object-fit: contain;
	}

	.op-attachment-wrapper {
		display: contents;
	}

	.image-broken {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		border-radius: 6px;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text-muted);
		font-size: 0.85rem;
		max-width: 300px;
	}

	.image-broken.hidden {
		display: none;
	}

	.image-broken-icon {
		font-size: 1.25rem;
		opacity: 0.6;
	}

	.image-broken-text {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.op-attachment-file {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		padding: 0.4rem 0.75rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 6px;
		color: var(--text);
		text-decoration: none;
		font-size: 0.85rem;
	}

	.op-preview {
		margin: 0;
		font-size: 0.9rem;
		color: var(--text-muted);
		line-height: 1.5;
	}

	.badge {
		font-size: 0.75rem;
		line-height: 1;
	}

	.badge-pin {
		color: var(--accent, #5865f2);
	}

	.badge-lock {
		color: var(--text-muted);
	}

	.error-state {
		text-align: center;
		color: var(--text-muted);
		padding: 3rem;
	}

	.locked-placeholder {
		padding: 0.75rem 1rem;
		text-align: center;
		color: var(--text-muted);
		font-size: 0.85rem;
		border-top: 1px solid var(--border);
	}

	/* ── Thread actions ── */
	.thread-actions {
		display: flex;
		gap: 0.4rem;
		flex-wrap: wrap;
	}

	.action-btn {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text-muted);
		font-size: 0.75rem;
		padding: 0.25rem 0.6rem;
		border-radius: 4px;
		cursor: pointer;
		white-space: nowrap;
	}

	.action-btn:hover {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.08));
	}

	.action-btn-danger:hover {
		color: var(--danger, #e74c3c);
		border-color: var(--danger, #e74c3c);
	}

	.replies-header {
		padding: 0.6rem 1.5rem;
		font-size: 0.85rem;
		font-weight: 600;
		color: var(--text-muted);
		border-bottom: 1px solid var(--border);
		text-transform: capitalize;
	}
</style>
