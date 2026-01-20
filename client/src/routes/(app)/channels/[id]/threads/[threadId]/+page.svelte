<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { untrack } from 'svelte';
	import { getThread, getThreadMessages as fetchThreadMessages, replyToThread, updateThreadReadState } from '$lib/api/threads.js';
	import { editMessage, deleteMessage } from '$lib/api/messages.js';
	import {
		getActiveThread, setActiveThread,
		getThreadMessages, setThreadMessages, prependThreadMessages,
		setThreadLoading, setThreadHasMore, hasMoreThreadMessages, isThreadLoading,
		getThreadTypingUsers, markThreadRead
	} from '$lib/stores/threads.svelte.js';
	import { getChannelById } from '$lib/stores/channels.svelte.js';
	import { getUser, hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { send } from '$lib/stores/ws.svelte.js';
	import MessageList from '$lib/components/MessageList.svelte';
	import MessageCompose from '$lib/components/MessageCompose.svelte';

	let channelId = $derived(page.params.id!);
	let threadId = $derived(page.params.threadId!);
	let highlightParam = $derived(page.url.searchParams.get('highlight'));
	let channel = $derived(getChannelById(channelId));

	let thread = $state<Awaited<ReturnType<typeof getThread>> | null>(null);
	let loadError = $state(false);
	let highlightMessageId = $state<string | null>(null);

	let messages = $derived(getThreadMessages(threadId));
	let loading = $derived(isThreadLoading(threadId));
	let canLoadMore = $derived(hasMoreThreadMessages(threadId));
	let typingUsers = $derived(getThreadTypingUsers(threadId));
	let isLocked = $derived((thread?.locked ?? 0) !== 0);
	let canSend = $derived(
		channelId && !isLocked
			? hasChannelPermission(channelId, PermissionName.SEND_MESSAGES)
			: isLocked
				? hasChannelPermission(channelId, PermissionName.SEND_IN_LOCKED_THREADS)
				: false
	);
	let canManageMessages = $derived(channelId ? hasChannelPermission(channelId, PermissionName.MANAGE_MESSAGES) : false);
	let canAddReactions = $derived(channelId ? hasChannelPermission(channelId, PermissionName.ADD_REACTIONS) : false);
	let currentUserId = $derived(getUser()?.id ?? '');

	let editingId = $state<string | null>(null);
	let editContent = $state('');
	let typingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let stopTypingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);

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

	function goBack() {
		setActiveThread(null);
		goto(`/channels/${channelId}`);
	}
</script>

<div class="thread-detail">
	<div class="thread-detail-header">
		<button class="back-btn" onclick={goBack}>← Back</button>
		<span class="thread-detail-title">
			{#if isLocked}<span class="lock-icon">🔒</span>{/if}
			{thread?.title || 'Thread'}
		</span>
		{#if channel}
			<span class="thread-channel-name"># {channel.name}</span>
		{/if}
	</div>

	{#if loadError}
		<div class="error-state">Thread not found or could not be loaded.</div>
	{:else}
		<MessageList
			{messages}
			{loading}
			{canLoadMore}
			{currentUserId}
			{editingId}
			{editContent}
			{typingUsers}
			{canManageMessages}
			{canAddReactions}
			{highlightMessageId}
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
	{/if}
</div>

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
		gap: 0.75rem;
		padding: 0.6rem 1rem;
		border-bottom: 1px solid var(--border);
	}

	.back-btn {
		background: none;
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		font-size: 0.85rem;
		padding: 0.2rem 0.4rem;
		border-radius: 4px;
	}

	.back-btn:hover {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.thread-detail-title {
		font-weight: 600;
		font-size: 0.95rem;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		display: flex;
		align-items: center;
		gap: 0.3rem;
	}

	.lock-icon {
		font-size: 0.8rem;
	}

	.thread-channel-name {
		font-size: 0.75rem;
		color: var(--text-muted);
		margin-left: auto;
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
</style>
