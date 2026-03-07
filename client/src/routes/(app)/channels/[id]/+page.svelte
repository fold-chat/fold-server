<script lang="ts">
	import { page } from '$app/state';
	import { untrack } from 'svelte';
	import { getMessages as fetchMessages, sendMessage, editMessage, deleteMessage, updateReadState } from '$lib/api/messages.js';
import { getThreads } from '$lib/api/threads.js';
	import type { Thread } from '$lib/api/threads.js';
	import { getMessages, setMessages, prependMessages, setLoading, setHasMore, hasMore, isLoading, setActiveChannelId, getTypingUsers } from '$lib/stores/messages.svelte.js';
import { markChannelRead, getChannelById, getCategories } from '$lib/stores/channels.svelte.js';
	import { getActiveThread, setActiveThread, findThreadByParentMessage, getChannelThreads, setChannelThreads, getPendingThread, setPendingThread } from '$lib/stores/threads.svelte.js';
	import { send } from '$lib/stores/ws.svelte.js';
	import { getUser, hasChannelPermission, hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import MessageList from '$lib/components/MessageList.svelte';
	import MessageCompose from '$lib/components/MessageCompose.svelte';
	import ThreadPanel from '$lib/components/ThreadPanel.svelte';
import ForumView from '$lib/components/ForumView.svelte';
import VoiceChannelView from '$lib/components/VoiceChannelView.svelte';
import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';

	let channelId = $derived(page.params.id!);
	let aroundMessageId = $derived(page.url.searchParams.get('around'));
	let channel = $derived(getChannelById(channelId));
	let isForumChannel = $derived(channel?.type === 'THREAD_CHANNEL');
	let isVoiceChannel = $derived(channel?.type === 'VOICE');
	let categoryName = $derived.by(() => {
		if (!channel?.category_id) return null;
		return getCategories().find(c => c.id === channel!.category_id)?.name ?? null;
	});
	let isArchived = $derived(!!channel?.archived_at);
	let canSend = $derived(!isArchived && hasChannelPermission(channelId, PermissionName.SEND_MESSAGES));
	let canUploadFiles = $derived(hasChannelPermission(channelId, PermissionName.UPLOAD_FILES));
	let canManageMessages = $derived(!isArchived && hasChannelPermission(channelId, PermissionName.MANAGE_MESSAGES));
	let canManageOwnMessages = $derived(!isArchived && hasChannelPermission(channelId, PermissionName.MANAGE_OWN_MESSAGES));
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
	let deleteConfirmOpen = $state(false);
	let pendingDeleteId = $state<string | null>(null);
	let deleteConfirmMessage = $state('Are you sure you want to delete this message? This cannot be undone.');

	// Clear thread panel when switching channels (separate effect so it only tracks channelId)
	$effect(() => {
		channelId;
		setActiveThread(null);
		setPendingThread(null);
	});

	$effect(() => {
		const chId = channelId;
		const around = aroundMessageId;
		const isVoice = channel?.type === 'VOICE';
		if (chId) {
			setActiveChannelId(chId);
			if (!isVoice) {
				untrack(() => {
					if (around) {
						loadMessagesAround(chId, around);
					} else {
						loadMessages(chId);
					}
					loadChannelThreads(chId);
				});
			}
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
		await sendMessage(channelId, content, attachmentIds);
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

	function handleDelete(id: string) {
		pendingDeleteId = id;
		const thread = findThreadByParentMessage(id);
		if (thread) {
			const count = thread.reply_count ?? 0;
			deleteConfirmMessage = `This message has a thread with ${count} ${count === 1 ? 'reply' : 'replies'}. Deleting this message will also permanently delete the thread and all its messages.`;
		} else {
			deleteConfirmMessage = 'Are you sure you want to delete this message? This cannot be undone.';
		}
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
{:else if isVoiceChannel}
	<VoiceChannelView {channelId} channelName={channel?.name ?? ''} />
{:else}
	<div class="channel-view">
		<div class="channel-main">
		<div class="channel-header">
				<div class="channel-header-info">
					{#if categoryName}
						<span class="breadcrumb-cat">{categoryName}</span>
						<span class="breadcrumb-sep">›</span>
					{/if}
					<span class="channel-title"># {channel?.name ?? ''}</span>
					{#if channel?.topic}
						<span class="header-divider"></span>
						<span class="channel-topic">{channel.topic}</span>
					{/if}
				</div>
			</div>
		{#if isArchived}
				<div class="archived-banner">This channel is archived — no new messages can be sent.</div>
			{/if}
			{#if channel?.description}
				<div class="channel-description">{channel.description}</div>
			{/if}
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

<ConfirmDialog
	open={deleteConfirmOpen}
	title="Delete Message"
	message={deleteConfirmMessage}
	onconfirm={confirmDelete}
	oncancel={cancelDelete}
/>

<style>
	.channel-view {
		flex: 1;
		display: flex;
		flex-direction: row;
		height: 100%;
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
		min-height: 44px;
		gap: 0.75rem;
		flex-shrink: 0;
	}

	.channel-header-info {
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

	.archived-banner {
		padding: 0.4rem 1rem;
		font-size: 0.78rem;
		color: var(--text-muted);
		background: color-mix(in srgb, var(--text-muted) 8%, transparent);
		border-bottom: 1px solid var(--border);
		text-align: center;
	}

</style>
