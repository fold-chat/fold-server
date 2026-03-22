<script lang="ts">
	import { page } from '$app/state';
	import { untrack } from 'svelte';
	import { getMessages as fetchMessages, sendMessage, editMessage, deleteMessage, updateReadState } from '$lib/api/messages.js';
	import { blockUser, unblockUser, getDmConversations as fetchDmConversations } from '$lib/api/dm.js';
	import { getMessages, setMessages, prependMessages, setLoading, setHasMore, hasMore, isLoading, setActiveChannelId, getTypingUsers } from '$lib/stores/messages.svelte.js';
import { markDmRead, getDmConversation, updateDmConversation, isBlocked as isUserBlocked, addBlock, removeBlock, isDmChannel, ensureDmLoaded } from '$lib/stores/dm.svelte.js';
	import { markChannelRead } from '$lib/stores/channels.svelte.js';
	import { send } from '$lib/stores/ws.svelte.js';
	import { getUser } from '$lib/stores/auth.svelte.js';
	import MessageList from '$lib/components/MessageList.svelte';
	import MessageCompose from '$lib/components/MessageCompose.svelte';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import type { ApiError } from '$lib/api/client.js';

	// Ensure DM data is loaded
	ensureDmLoaded();

	let dmChannelId = $derived(page.params.dmChannelId!);
	let conversation = $derived(getDmConversation(dmChannelId));
	let isValidDm = $derived(isDmChannel(dmChannelId));
	let me = $derived(getUser());
	let otherParticipant = $derived(conversation?.participants.find(p => p.id !== me?.id) ?? conversation?.participants[0]);
	let isConvBlocked = $derived(conversation?.is_blocked ?? false);
	let iBlockedThem = $derived(otherParticipant ? isUserBlocked(otherParticipant.id) : false);

	let editingId = $state<string | null>(null);
	let editContent = $state('');
	let typingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let stopTypingTimeout = $state<ReturnType<typeof setTimeout> | null>(null);
	let deleteConfirmOpen = $state(false);
	let pendingDeleteId = $state<string | null>(null);
	let blockError = $state('');

	$effect(() => {
		const chId = dmChannelId;
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
		} catch { /* */ } finally {
			setLoading(chId, false);
		}
	}

	async function loadOlder() {
		const msgs = getMessages(dmChannelId);
		if (msgs.length === 0 || !hasMore(dmChannelId)) return;
		setLoading(dmChannelId, true);
		try {
			const older = await fetchMessages(dmChannelId, { before: msgs[0].id, limit: 50 });
			prependMessages(dmChannelId, older);
			setHasMore(dmChannelId, older.length >= 50);
		} catch { /* */ } finally {
			setLoading(dmChannelId, false);
		}
	}

	async function handleSend(content: string, attachmentIds?: string[]) {
		stopTyping();
		try {
			await sendMessage(dmChannelId, content, attachmentIds);
		} catch (err) {
			const apiErr = err as ApiError;
			if (apiErr?.error === 'dm_blocked') {
				// Other user blocked after load — update conversation
				updateDmConversation(dmChannelId, { is_blocked: true });
			}
		}
	}

	function handleTyping() {
		if (stopTypingTimeout) clearTimeout(stopTypingTimeout);
		stopTypingTimeout = setTimeout(() => {
			stopTypingTimeout = null;
			stopTyping();
		}, 5000);
		if (typingTimeout) return;
		send('TYPING', { channel_id: dmChannelId });
		typingTimeout = setTimeout(() => { typingTimeout = null; }, 3000);
	}

	function stopTyping() {
		if (typingTimeout) { clearTimeout(typingTimeout); typingTimeout = null; }
		if (stopTypingTimeout) { clearTimeout(stopTypingTimeout); stopTypingTimeout = null; }
		send('TYPING_STOP', { channel_id: dmChannelId });
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

	function startEdit(id: string, content: string) {
		editingId = id;
		editContent = content;
	}

	function cancelEdit() {
		editingId = null;
		editContent = '';
	}

	function markRead(chId: string) {
		const msgs = getMessages(chId);
		if (msgs.length > 0) {
			const last = msgs[msgs.length - 1];
			markDmRead(chId);
			markChannelRead(chId, last.id);
			updateReadState(chId, last.id).catch(() => {});
		}
	}

	async function toggleBlock() {
		if (!otherParticipant) return;
		blockError = '';
		try {
			if (iBlockedThem) {
				await unblockUser(otherParticipant.id);
				removeBlock(otherParticipant.id);
				// Re-fetch to get updated is_blocked (other user may still have a block)
				try {
					const convs = await fetchDmConversations();
					const updated = convs.find(c => c.channel_id === dmChannelId);
					if (updated) updateDmConversation(dmChannelId, { is_blocked: updated.is_blocked });
				} catch { /* */ }
			} else {
				await blockUser(otherParticipant.id);
				addBlock(otherParticipant.id);
				updateDmConversation(dmChannelId, { is_blocked: true });
			}
		} catch {
			blockError = 'Failed to update block status';
		}
	}
</script>

{#if !isValidDm}
<div class="not-found">Conversation not found.</div>
{:else}
<div class="dm-channel-view">
	<div class="dm-header">
		<div class="dm-header-info">
			{#if otherParticipant?.avatar_url}
				<img class="dm-header-avatar" src={otherParticipant.avatar_url} alt="" />
			{:else}
				<span class="dm-header-avatar-placeholder">{(otherParticipant?.display_name || otherParticipant?.username || '?').charAt(0).toUpperCase()}</span>
			{/if}
			<span class="dm-header-name">{otherParticipant?.display_name || otherParticipant?.username || 'Unknown'}</span>
		</div>
		<div class="dm-header-actions">
			<button class="block-btn" class:danger={!iBlockedThem} onclick={toggleBlock}>
				{iBlockedThem ? 'Unblock' : 'Block'}
			</button>
		</div>
	</div>

	{#if isConvBlocked}
		<div class="blocked-banner">You cannot send messages in this conversation.</div>
	{/if}
	{#if blockError}
		<div class="blocked-banner">{blockError}</div>
	{/if}

	<MessageList
		messages={getMessages(dmChannelId)}
		loading={isLoading(dmChannelId)}
		canLoadMore={hasMore(dmChannelId)}
		currentUserId={me?.id ?? ''}
		{editingId}
		{editContent}
		typingUsers={getTypingUsers(dmChannelId)}
		canManageMessages={false}
		canManageOwnMessages={!isConvBlocked}
		canCreateThreads={false}
		canAddReactions={!isConvBlocked}
		highlightMessageId={null}
		threadLookup={() => undefined}
		onLoadMore={loadOlder}
		onStartEdit={startEdit}
		onCancelEdit={cancelEdit}
		onSaveEdit={handleEdit}
		onDelete={handleDelete}
		onStartThread={() => {}}
		onOpenThread={() => {}}
	/>

	<MessageCompose
		onSend={handleSend}
		onTyping={handleTyping}
		disabled={isConvBlocked}
		canUploadFiles={!isConvBlocked}
		channelId={dmChannelId}
	/>
</div>

{/if}

<ConfirmDialog
	open={deleteConfirmOpen}
	title="Delete Message"
	message="Are you sure you want to delete this message? This cannot be undone."
	onconfirm={confirmDelete}
	oncancel={() => { deleteConfirmOpen = false; pendingDeleteId = null; }}
/>

<style>
	.dm-channel-view {
		flex: 1;
		display: flex;
		flex-direction: column;
		height: 100%;
		min-width: 0;
	}

	.dm-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.5rem 1rem;
		border-bottom: 1px solid var(--border);
		min-height: 44px;
		flex-shrink: 0;
	}

	.dm-header-info {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}

	.dm-header-avatar {
		width: 28px;
		height: 28px;
		border-radius: 50%;
		object-fit: cover;
	}

	.dm-header-avatar-placeholder {
		width: 28px;
		height: 28px;
		border-radius: 50%;
		background: var(--bg-active, #3a3d41);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.75rem;
		font-weight: 600;
		color: var(--text-muted);
	}

	.dm-header-name {
		font-weight: 600;
		font-size: 0.9rem;
	}

	.dm-header-actions {
		display: flex;
		gap: 0.5rem;
	}

	.block-btn {
		padding: 0.3rem 0.6rem;
		font-size: 0.75rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 4px;
		cursor: pointer;
		transition: background 0.12s, color 0.12s;
	}

	.block-btn:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.block-btn.danger {
		color: var(--danger, #e74c3c);
		border-color: color-mix(in srgb, var(--danger, #e74c3c) 30%, transparent);
	}

	.block-btn.danger:hover {
		background: color-mix(in srgb, var(--danger, #e74c3c) 15%, transparent);
	}

	.blocked-banner {
		padding: 0.4rem 1rem;
		font-size: 0.78rem;
		color: var(--text-muted);
		background: color-mix(in srgb, var(--danger, #e74c3c) 8%, transparent);
		border-bottom: 1px solid var(--border);
		text-align: center;
	}

	.not-found {
		flex: 1;
		display: flex;
		align-items: center;
		justify-content: center;
		color: var(--text-muted);
		font-size: 0.9rem;
	}
</style>
