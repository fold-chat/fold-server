<script lang="ts">
	import { untrack } from 'svelte';
	import { getThreadMessages as fetchThreadMessages, replyToThread, updateThreadReadState } from '$lib/api/threads.js';
	import { editMessage, deleteMessage } from '$lib/api/messages.js';
	import {
		getActiveThread, closeThreadPanel, getThreadMessages, setThreadMessages,
		prependThreadMessages, setThreadLoading, setThreadHasMore,
		hasMoreThreadMessages, isThreadLoading, getThreadTypingUsers, markThreadRead
	} from '$lib/stores/threads.svelte.js';
	import { getUser, hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { send } from '$lib/stores/ws.svelte.js';
	import { formatTimestamp } from '$lib/utils/markdown.js';
	import MessageList from './MessageList.svelte';
	import MessageCompose from './MessageCompose.svelte';

	let editingId = $state<string | null>(null);
	let editContent = $state('');
	let typingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let stopTypingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);

	let thread = $derived(getActiveThread());
	let threadId = $derived(thread?.id ?? null);
	let channelId = $derived(thread?.channel_id ?? '');
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

	$effect(() => {
		const tId = threadId;
		if (tId) {
			untrack(() => loadMessages(tId));
		}
	});

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
		if (!threadId) return;
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
		if (!thread) return 'Thread';
		if (thread.title) return thread.title;
		return 'Thread';
	}
</script>

{#if thread}
	<div class="thread-panel">
		<div class="thread-header">
			<div class="thread-title">
				{#if isLocked}<span class="lock-icon" title="Locked">🔒</span>{/if}
				<span>{headerLabel()}</span>
			</div>
			<button class="close-btn" onclick={closeThreadPanel} title="Close thread">✕</button>
		</div>

		{#if thread.parent_message_id}
			<div class="thread-context">
				<span class="context-label">Thread on message by</span>
				<span class="context-author">{thread.author_display_name || thread.author_username || 'Unknown'}</span>
				<span class="context-time">{formatTimestamp(thread.created_at)}</span>
			</div>
		{/if}

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

	.thread-context {
		padding: 0.4rem 1rem;
		font-size: 0.75rem;
		color: var(--text-muted);
		border-bottom: 1px solid var(--border);
		display: flex;
		gap: 0.35rem;
		align-items: baseline;
	}

	.context-author {
		font-weight: 600;
		color: var(--text);
	}

	.context-time {
		font-size: 0.65rem;
	}

	.locked-placeholder {
		padding: 0.75rem 1rem;
		text-align: center;
		color: var(--text-muted);
		font-size: 0.85rem;
		border-top: 1px solid var(--border);
	}
</style>
