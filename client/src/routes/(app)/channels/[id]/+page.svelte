<script lang="ts">
	import { page } from '$app/state';
	import { untrack } from 'svelte';
	import { getMessages as fetchMessages, sendMessage, editMessage, deleteMessage, updateReadState } from '$lib/api/messages.js';
import { getThreads } from '$lib/api/threads.js';
	import type { Thread } from '$lib/api/threads.js';
	import { getMessages, setMessages, prependMessages, setLoading, setHasMore, hasMore, isLoading, setActiveChannelId, getTypingUsers } from '$lib/stores/messages.svelte.js';
	import { markChannelRead, getChannelById } from '$lib/stores/channels.svelte.js';
	import { getActiveThread, setActiveThread, findThreadByParentMessage, getChannelThreads, setChannelThreads, getPendingThread, setPendingThread } from '$lib/stores/threads.svelte.js';
	import { send } from '$lib/stores/ws.svelte.js';
	import { getUser, hasChannelPermission, hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import MessageList from '$lib/components/MessageList.svelte';
	import MessageCompose from '$lib/components/MessageCompose.svelte';
	import ThreadPanel from '$lib/components/ThreadPanel.svelte';
import ForumView from '$lib/components/ForumView.svelte';
	import VoicePanel from '$lib/components/VoicePanel.svelte';

	let channelId = $derived(page.params.id!);
	let aroundMessageId = $derived(page.url.searchParams.get('around'));
	let channel = $derived(getChannelById(channelId));
	let isForumChannel = $derived(channel?.type === 'THREAD_CHANNEL');
	let canSend = $derived(hasChannelPermission(channelId, PermissionName.SEND_MESSAGES));
	let canUploadFiles = $derived(hasChannelPermission(channelId, PermissionName.UPLOAD_FILES));
	let canManageMessages = $derived(hasChannelPermission(channelId, PermissionName.MANAGE_MESSAGES));
	let canManageOwnMessages = $derived(hasChannelPermission(channelId, PermissionName.MANAGE_OWN_MESSAGES));
	let canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));
	let canCreateThreads = $derived(hasChannelPermission(channelId, PermissionName.CREATE_THREADS));
	let canAddReactions = $derived(hasChannelPermission(channelId, PermissionName.ADD_REACTIONS));
	let editingId = $state<string | null>(null);
	let editContent = $state('');
	let typingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let stopTypingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let activeThread = $derived(getActiveThread());
	let pendingThread = $derived(getPendingThread());
	let highlightMessageId = $state<string | null>(null);

	$effect(() => {
		const chId = channelId;
		const around = aroundMessageId;
		if (chId) {
			setActiveChannelId(chId);
			untrack(() => {
				if (around) {
					loadMessagesAround(chId, around);
				} else {
					loadMessages(chId);
				}
				loadChannelThreads(chId);
			});
		}
		return () => setActiveChannelId(null);
	});

	async function loadChannelThreads(chId: string) {
		if (getChannelThreads(chId).length > 0) return;
		try {
			const threads = await getThreads(chId, { limit: 100 });
			setChannelThreads(chId, threads);
		} catch {
			// non-critical — thread indicators just won't show
		}
	}

	async function loadMessages(chId: string) {
		highlightMessageId = null;
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

	async function loadMessagesAround(chId: string, messageId: string) {
		setLoading(chId, true);
		try {
			const msgs = await fetchMessages(chId, { around: messageId, limit: 25 });
			setMessages(chId, msgs);
			setHasMore(chId, true);
			highlightMessageId = messageId;
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

	function handleStartThread(messageId: string) {
		setPendingThread({ parent_message_id: messageId, channel_id: channelId });
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

{#if isForumChannel}
	<ForumView {channelId} channelName={channel?.name ?? ''} channelTopic={channel?.topic ?? null} channelDescription={channel?.description ?? null} />
{:else}
	<div class="channel-view">
		<div class="channel-main">
		<div class="channel-header">
				<div class="channel-header-info">
					<span class="channel-title"># {channel?.name ?? ''}</span>
					{#if channel?.topic}
						<span class="header-divider"></span>
						<span class="channel-topic">{channel.topic}</span>
					{/if}
				</div>
			</div>
			{#if channel?.description}
				<div class="channel-description">{channel.description}</div>
			{/if}
			<VoicePanel />
			<MessageList
				messages={getMessages(channelId)}
				loading={isLoading(channelId)}
				canLoadMore={hasMore(channelId)}
				currentUserId={getUser()?.id ?? ''}
				{editingId}
				{editContent}
				typingUsers={getTypingUsers(channelId)}
			{canManageMessages}
				{canManageOwnMessages}
				{canCreateThreads}
				{canAddReactions}
				{highlightMessageId}
				{threadLookup}
				onLoadMore={loadOlder}
				onStartEdit={startEdit}
				onCancelEdit={cancelEdit}
				onSaveEdit={handleEdit}
				onDelete={handleDelete}
				onStartThread={handleStartThread}
				onOpenThread={handleOpenThread}
			/>
			<MessageCompose onSend={handleSend} onTyping={handleTyping} disabled={!canSend} {canUploadFiles} {channelId} />
		</div>
	{#if activeThread || pendingThread}
		<ThreadPanel />
	{/if}
	</div>
{/if}

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
		align-items: center;
		justify-content: space-between;
		padding: 0.5rem 1rem;
		border-bottom: 1px solid var(--border);
		min-height: 0;
		gap: 0.75rem;
	}

	.channel-header-info {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-width: 0;
		overflow: hidden;
	}

	.channel-title {
		font-weight: 600;
		font-size: 0.9rem;
		white-space: nowrap;
		flex-shrink: 0;
	}

	.header-divider {
		width: 1px;
		height: 1rem;
		background: var(--border);
		flex-shrink: 0;
	}

	.channel-topic {
		font-size: 0.8rem;
		color: var(--text-muted);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.channel-description {
		padding: 0.35rem 1rem;
		font-size: 0.78rem;
		color: var(--text-muted);
		border-bottom: 1px solid var(--border);
		line-height: 1.4;
	}

</style>
