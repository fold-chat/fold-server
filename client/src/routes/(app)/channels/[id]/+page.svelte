<script lang="ts">
	import { page } from '$app/state';
	import { untrack } from 'svelte';
	import { getMessages as fetchMessages, sendMessage, editMessage, deleteMessage, updateReadState } from '$lib/api/messages.js';
	import { createThread } from '$lib/api/threads.js';
	import type { Thread } from '$lib/api/threads.js';
	import { getMessages, setMessages, prependMessages, setLoading, setHasMore, hasMore, isLoading, setActiveChannelId, getTypingUsers } from '$lib/stores/messages.svelte.js';
	import { markChannelRead } from '$lib/stores/channels.svelte.js';
	import { getActiveThread, setActiveThread, findThreadByParentMessage, getChannelThreads } from '$lib/stores/threads.svelte.js';
	import { send } from '$lib/stores/ws.svelte.js';
	import { getUser, hasChannelPermission, hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import MessageList from '$lib/components/MessageList.svelte';
	import MessageCompose from '$lib/components/MessageCompose.svelte';
	import ThreadPanel from '$lib/components/ThreadPanel.svelte';

	let channelId = $derived(page.params.id!);
	let canSend = $derived(hasChannelPermission(channelId, PermissionName.SEND_MESSAGES));
	let canManageMessages = $derived(hasChannelPermission(channelId, PermissionName.MANAGE_MESSAGES));
	let canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));
	let canCreateThreads = $derived(hasChannelPermission(channelId, PermissionName.CREATE_THREADS));
	let editingId = $state<string | null>(null);
	let editContent = $state('');
	let typingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let stopTypingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let activeThread = $derived(getActiveThread());

	$effect(() => {
		const chId = channelId;
		if (chId) {
			setActiveChannelId(chId);
			untrack(() => loadMessages(chId));
		}
		return () => setActiveChannelId(null);
	});

	async function loadMessages(chId: string) {
		if (getMessages(chId).length > 0) {
			markRead(chId);
			return;
		}
		setLoading(chId, true);
		try {
			const msgs = await fetchMessages(chId, { limit: 50 });
			setMessages(chId, msgs);
			setHasMore(chId, msgs.length >= 50);
			markRead(chId);
		} catch {
			// handle error
		} finally {
			setLoading(chId, false);
		}
	}

	async function loadOlder() {
		const msgs = getMessages(channelId);
		if (msgs.length === 0 || !hasMore(channelId)) return;
		const oldest = msgs[0];
		setLoading(channelId, true);
		try {
			const older = await fetchMessages(channelId, { before: oldest.id, limit: 50 });
			prependMessages(channelId, older);
			setHasMore(channelId, older.length >= 50);
		} catch {
			// handle error
		} finally {
			setLoading(channelId, false);
		}
	}

	async function handleSend(content: string, attachmentIds?: string[]) {
		stopTyping();
		try {
			await sendMessage(channelId, content, attachmentIds);
		} catch {
			// handle error
		}
	}

	function handleTyping() {
		// Reset idle stop timer on every keystroke
		if (stopTypingTimeout) clearTimeout(stopTypingTimeout);
		stopTypingTimeout = setTimeout(() => {
			stopTypingTimeout = null;
			stopTyping();
		}, 5000);

		if (typingTimeout) return;
		send('TYPING', { channel_id: channelId });
		typingTimeout = setTimeout(() => {
			typingTimeout = null;
		}, 3000);
	}

	function stopTyping() {
		if (typingTimeout) {
			clearTimeout(typingTimeout);
			typingTimeout = null;
		}
		if (stopTypingTimeout) {
			clearTimeout(stopTypingTimeout);
			stopTypingTimeout = null;
		}
		send('TYPING_STOP', { channel_id: channelId });
	}

	async function handleEdit(id: string, content: string) {
		try {
			await editMessage(id, content);
			editingId = null;
		} catch {
			// handle error
		}
	}

	async function handleDelete(id: string) {
		try {
			await deleteMessage(id);
		} catch {
			// handle error
		}
	}

	function startEdit(id: string, content: string) {
		editingId = id;
		editContent = content;
	}

	function cancelEdit() {
		editingId = null;
		editContent = '';
	}

	async function handleStartThread(messageId: string) {
		// Prompt for first message content — for now use a simple prompt
		const content = prompt('Start a thread — enter your first reply:');
		if (!content?.trim()) return;
		try {
			const thread = await createThread(channelId, { parent_message_id: messageId, content: content.trim() });
			setActiveThread(thread);
		} catch {
			// handle error
		}
	}

	function handleOpenThread(thread: Thread) {
		setActiveThread(thread);
	}

	function threadLookup(messageId: string): Thread | undefined {
		return findThreadByParentMessage(messageId);
	}

	function markRead(chId: string) {
		const msgs = getMessages(chId);
		if (msgs.length > 0) {
			const lastMsg = msgs[msgs.length - 1];
			markChannelRead(chId, lastMsg.id);
			updateReadState(chId, lastMsg.id).catch(() => {});
		}
	}
</script>

<div class="channel-view">
	<div class="channel-main">
		<div class="channel-header">
			{#if canManageChannels}
				<a href="/channels/{channelId}/settings" class="channel-settings">Permissions</a>
			{/if}
		</div>
		<MessageList
			messages={getMessages(channelId)}
			loading={isLoading(channelId)}
			canLoadMore={hasMore(channelId)}
			currentUserId={getUser()?.id ?? ''}
			{editingId}
			{editContent}
			typingUsers={getTypingUsers(channelId)}
			{canManageMessages}
			{canCreateThreads}
			{threadLookup}
			onLoadMore={loadOlder}
			onStartEdit={startEdit}
			onCancelEdit={cancelEdit}
			onSaveEdit={handleEdit}
			onDelete={handleDelete}
			onStartThread={handleStartThread}
			onOpenThread={handleOpenThread}
		/>
		<MessageCompose onSend={handleSend} onTyping={handleTyping} disabled={!canSend} />
	</div>
	{#if activeThread}
		<ThreadPanel />
	{/if}
</div>

<style>
	.channel-view {
		flex: 1;
		display: flex;
		flex-direction: row;
		height: 100vh;
		min-width: 0;
	}

	.channel-main {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.channel-header {
		display: flex;
		justify-content: flex-end;
		padding: 0.4rem 1rem;
		border-bottom: 1px solid var(--border);
		min-height: 0;
	}

	.channel-settings {
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.channel-settings:hover {
		color: var(--text);
	}
</style>
