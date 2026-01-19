<script lang="ts">
	import { untrack } from 'svelte';
	import { createThread, getThreadMessages as fetchThreadMessages, replyToThread, updateThreadReadState } from '$lib/api/threads.js';
	import { editMessage, deleteMessage, getMessage } from '$lib/api/messages.js';
	import type { Message } from '$lib/api/messages.js';
	import {
		getActiveThread, closeThreadPanel, getThreadMessages, setThreadMessages,
		prependThreadMessages, setThreadLoading, setThreadHasMore,
		hasMoreThreadMessages, isThreadLoading, getThreadTypingUsers, markThreadRead,
		getPendingThread, setActiveThread, addChannelThread
	} from '$lib/stores/threads.svelte.js';
	import { getUser, hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { send } from '$lib/stores/ws.svelte.js';
	import { formatTimestamp, renderMarkdown } from '$lib/utils/markdown.js';
	import MessageList from './MessageList.svelte';
	import MessageCompose from './MessageCompose.svelte';

	let editingId = $state<string | null>(null);
	let editContent = $state('');
	let typingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let stopTypingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let parentMessage = $state<Message | null>(null);
	let parentMessageLoading = $state(false);

	let thread = $derived(getActiveThread());
	let pending = $derived(getPendingThread());
	let isPending = $derived(pending !== null && thread === null);
	let threadId = $derived(thread?.id ?? null);
	let channelId = $derived(thread?.channel_id ?? pending?.channel_id ?? '');
	let messages = $derived(threadId ? getThreadMessages(threadId) : []);
	let loading = $derived(threadId ? isThreadLoading(threadId) : false);
	let canLoadMore = $derived(threadId ? hasMoreThreadMessages(threadId) : false);
	let typingUsers = $derived(threadId ? getThreadTypingUsers(threadId) : []);
	let isLocked = $derived((thread?.locked ?? 0) !== 0);
	let canSend = $derived(
		channelId && !isLocked
			? hasChannelPermission(channelId, PermissionName.SEND_MESSAGES)
			: isLocked
				? hasChannelPermission(channelId, PermissionName.SEND_IN_LOCKED_THREADS)
				: false
	);
	let canManageMessages = $derived(channelId ? hasChannelPermission(channelId, PermissionName.MANAGE_MESSAGES) : false);
	let currentUserId = $derived(getUser()?.id ?? '');
	let isVisible = $derived(thread !== null || pending !== null);

	$effect(() => {
		const tId = threadId;
		if (tId) {
			untrack(() => loadMessages(tId));
		}
	});

	$effect(() => {
		const pmId = thread?.parent_message_id;
		if (pmId) {
			untrack(() => fetchParentMessage(pmId));
		} else {
			parentMessage = null;
		}
	});

	async function fetchParentMessage(messageId: string) {
		parentMessageLoading = true;
		try {
			parentMessage = await getMessage(messageId);
		} catch {
			parentMessage = null;
		} finally {
			parentMessageLoading = false;
		}
	}

	async function loadMessages(tId: string) {
		if (getThreadMessages(tId).length > 0) {
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
		if (!threadId) return;
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
			if (threadId) setThreadLoading(threadId, false);
		}
	}

	async function handleSend(content: string, attachmentIds?: string[]) {
		stopTyping();
		// Pending thread: create on first message
		if (isPending && pending) {
			try {
				const created = await createThread(pending.channel_id, {
					parent_message_id: pending.parent_message_id,
					content,
					attachment_ids: attachmentIds
				});
				addChannelThread(created);
				setActiveThread(created); // clears pending
			} catch {
				// handle error
			}
			return;
		}
		if (!threadId) return;
		try {
			await replyToThread(threadId, content, attachmentIds);
			doMarkRead(threadId);
		} catch {
			// handle error
		}
	}

	function handleTyping() {
		if (isPending) return; // no typing events for pending threads
		if (stopTypingTimeout) clearTimeout(stopTypingTimeout);
		stopTypingTimeout = setTimeout(() => {
			stopTypingTimeout = null;
			stopTyping();
		}, 5000);

		if (typingTimeout) return;
		if (threadId) send('TYPING', { thread_id: threadId });
		typingTimeout = setTimeout(() => { typingTimeout = null; }, 3000);
	}

	function stopTyping() {
		if (typingTimeout) { clearTimeout(typingTimeout); typingTimeout = null; }
		if (stopTypingTimeout) { clearTimeout(stopTypingTimeout); stopTypingTimeout = null; }
		if (threadId) send('TYPING_STOP', { thread_id: threadId });
	}

	async function handleEdit(id: string, content: string) {
		try {
			await editMessage(id, content);
			editingId = null;
		} catch { /* */ }
	}

	async function handleDelete(id: string) {
		try { await deleteMessage(id); } catch { /* */ }
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

	function headerLabel(): string {
		if (isPending) return 'New Thread';
		if (!thread) return 'Thread';
		if (thread.title) return thread.title;
		return 'Thread';
	}
</script>

{#if isVisible}
	<div class="thread-panel">
		<div class="thread-header">
			<div class="thread-title">
				{#if isLocked}<span class="lock-icon" title="Locked">🔒</span>{/if}
				<span>{headerLabel()}</span>
			</div>
			<button class="close-btn" onclick={closeThreadPanel} title="Close thread">✕</button>
		</div>

		{#if thread?.parent_message_id}
			<div class="parent-message">
				{#if parentMessageLoading}
					<div class="parent-loading">Loading original message...</div>
				{:else if parentMessage}
					<div class="parent-header">
						<span class="parent-author">{parentMessage.author_display_name || parentMessage.author_username || 'Unknown'}</span>
						<span class="parent-time">{formatTimestamp(parentMessage.created_at)}</span>
					</div>
					<div class="parent-content">{@html renderMarkdown(parentMessage.content)}</div>
				{/if}
			</div>
		{/if}

		{#if !isPending}
			<MessageList
				{messages}
				{loading}
				{canLoadMore}
				{currentUserId}
				{editingId}
				{editContent}
				{typingUsers}
				{canManageMessages}
				onLoadMore={loadOlder}
				onStartEdit={startEdit}
				onCancelEdit={cancelEdit}
				onSaveEdit={handleEdit}
				onDelete={handleDelete}
			/>
		{:else}
			<div class="pending-hint">Send a message to start this thread</div>
		{/if}

		{#if isLocked && !canSend}
			<div class="locked-placeholder">🔒 This thread is locked</div>
		{:else}
			<MessageCompose onSend={handleSend} onTyping={handleTyping} disabled={!canSend} />
		{/if}
	</div>
{/if}

<style>
	.thread-panel {
		width: 400px;
		min-width: 300px;
		max-width: 50%;
		display: flex;
		flex-direction: column;
		height: 100vh;
		border-left: 1px solid var(--border);
		background: var(--bg);
	}

	.thread-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.6rem 1rem;
		border-bottom: 1px solid var(--border);
		min-height: 0;
	}

	.thread-title {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		font-weight: 600;
		font-size: 0.9rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.lock-icon {
		font-size: 0.8rem;
	}

	.close-btn {
		background: none;
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		font-size: 1rem;
		padding: 0.25rem 0.4rem;
		border-radius: 4px;
	}

	.close-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	.parent-message {
		padding: 0.6rem 1rem;
		border-bottom: 1px solid var(--border);
		background: var(--bg-surface, rgba(255, 255, 255, 0.02));
		flex-shrink: 0;
		max-height: 200px;
		overflow-y: auto;
	}

	.parent-header {
		display: flex;
		align-items: baseline;
		gap: 0.35rem;
		margin-bottom: 0.25rem;
	}

	.parent-author {
		font-weight: 600;
		font-size: 0.8rem;
		color: var(--text);
	}

	.parent-time {
		font-size: 0.65rem;
		color: var(--text-muted);
	}

	.parent-content {
		font-size: 0.85rem;
		color: var(--text-muted);
		line-height: 1.4;
	}

	.parent-content :global(p) {
		margin: 0;
	}

	.parent-loading {
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.locked-placeholder {
		padding: 0.75rem 1rem;
		text-align: center;
		color: var(--text-muted);
		font-size: 0.85rem;
		border-top: 1px solid var(--border);
	}

	.pending-hint {
		flex: 1;
		display: flex;
		align-items: center;
		justify-content: center;
		color: var(--text-muted);
		font-size: 0.85rem;
	}
</style>
