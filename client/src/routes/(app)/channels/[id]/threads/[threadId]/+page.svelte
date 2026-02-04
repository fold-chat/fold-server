<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { untrack } from 'svelte';
import { getThread, getThreadMessages as fetchThreadMessages, replyToThread, updateThreadReadState, updateThread, deleteThread as apiDeleteThread } from '$lib/api/threads.js';
import { editMessage, deleteMessage } from '$lib/api/messages.js';
	import {
		getActiveThread, setActiveThread,
		getThreadMessages, setThreadMessages, prependThreadMessages,
		setThreadLoading, setThreadHasMore, hasMoreThreadMessages, isThreadLoading,
		getThreadTypingUsers, markThreadRead
	} from '$lib/stores/threads.svelte.js';
	import { getChannelById, getCategories } from '$lib/stores/channels.svelte.js';
	import { getUser, hasChannelPermission } from '$lib/stores/auth.svelte.js';
import { PermissionName } from '$lib/permissions.js';
import { renderMarkdown, contentPreview } from '$lib/utils/markdown.js';
	import CollapsibleContent from '$lib/components/CollapsibleContent.svelte';
	import { send } from '$lib/stores/ws.svelte.js';
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

	{#if thread}
		<div class="thread-hero">
			<div class="op-author">
				{#if originalPost?.author_avatar_url || thread.author_avatar_url}
					<img class="op-avatar" src={originalPost?.author_avatar_url || thread.author_avatar_url} alt="" />
				{:else}
					<div class="op-avatar op-avatar-fallback">{(thread.author_display_name || thread.author_username || '?')[0].toUpperCase()}</div>
				{/if}
				<div class="op-author-info">
					<span class="op-name">{thread.author_display_name || thread.author_username || 'Unknown'}</span>
					<span class="op-time">{timeAgo(thread.created_at)}</span>
				</div>
			</div>
			<h1 class="thread-title">{thread.title || 'Thread'}</h1>
			{#if originalPost}
				<CollapsibleContent>
					<div class="op-content">{@html renderMarkdown(originalPost.content, { mentions: originalPost.mentions, mention_roles: originalPost.mention_roles, mention_everyone: originalPost.mention_everyone })}</div>
					{#if originalPost.attachments?.length}
						<div class="op-attachments">
							{#each originalPost.attachments as att}
								{#if att.mime_type.startsWith('image/')}
									<img class="op-attachment-img" src={att.url} alt={att.original_name} />
								{:else}
									<a class="op-attachment-file" href={att.url} download={att.original_name}>
										📄 {att.original_name}
									</a>
								{/if}
							{/each}
						</div>
					{/if}
				</CollapsibleContent>
			{:else if thread.first_message_content}
				<p class="op-preview">{contentPreview(thread.first_message_content)}</p>
			{/if}
			<div class="thread-meta">
				<span class="meta-item">💬 {replyCountLabel}</span>
				{#if thread.pinned}
					<span class="badge badge-pin">📌 Pinned</span>
				{/if}
				{#if isLocked}
					<span class="badge badge-lock">🔒 Locked</span>
				{/if}
			</div>
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
			onLoadMore={loadOlder}
			onStartEdit={startEdit}
			onCancelEdit={cancelEdit}
			onSaveEdit={handleEdit}
			onDelete={handleDelete}
		/>

		{#if isLocked && !canSend}
			<div class="locked-placeholder">🔒 This thread is locked</div>
		{:else}
			<MessageCompose onSend={handleSend} onTyping={handleTyping} disabled={!canSend} {canUploadFiles} forumMode />
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

	/* ── Thread hero ── */
	.thread-hero {
		padding: 1.25rem 1.5rem;
		border-bottom: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.op-author {
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}

	.op-avatar {
		width: 40px;
		height: 40px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.op-avatar-fallback {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--accent, #5865f2);
		color: white;
		font-size: 1rem;
		font-weight: 600;
	}

	.op-author-info {
		display: flex;
		flex-direction: column;
	}

	.op-name {
		font-weight: 600;
		font-size: 0.9rem;
	}

	.op-time {
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.thread-title {
		font-size: 1.5rem;
		font-weight: 700;
		margin: 0;
		line-height: 1.3;
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

	.thread-meta {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-size: 0.8rem;
		color: var(--text-muted);
		flex-wrap: wrap;
		padding-top: 0.5rem;
		border-top: 1px solid var(--border);
	}

	.badge {
		font-size: 0.68rem;
		font-weight: 600;
		padding: 0.1rem 0.4rem;
		border-radius: 4px;
		white-space: nowrap;
	}

	.badge-pin {
		color: var(--accent, #5865f2);
		background: rgba(88, 101, 242, 0.12);
	}

	.badge-lock {
		color: var(--text-muted);
		background: rgba(255, 255, 255, 0.06);
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
</style>
