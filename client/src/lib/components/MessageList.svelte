<script lang="ts">
import type { Message } from '$lib/api/messages.js';
	import type { Thread } from '$lib/api/threads.js';
	import { addReaction, removeReaction } from '$lib/api/reactions.js';
	import { renderMarkdown, formatTimestamp } from '$lib/utils/markdown.js';
	import EmojiPicker from './EmojiPicker.svelte';
	import klipyLogo from '$lib/assets/klipy.svg';
	import { tick } from 'svelte';

	let {
		messages,
		loading = false,
		canLoadMore = false,
		currentUserId,
		editingId = null,
		editContent = '',
		typingUsers = [],
		canManageMessages = false,
		canManageOwnMessages = false,
		canCreateThreads = false,
		canAddReactions = false,
		highlightMessageId = null,
		threadLookup = undefined,
		onLoadMore,
		onStartEdit,
		onCancelEdit,
		onSaveEdit,
		onDelete,
		onStartThread,
		onOpenThread
	}: {
		messages: Message[];
		loading?: boolean;
		canLoadMore?: boolean;
		currentUserId: string;
		editingId?: string | null;
		editContent?: string;
		typingUsers?: string[];
		canManageMessages?: boolean;
		canManageOwnMessages?: boolean;
		canCreateThreads?: boolean;
		canAddReactions?: boolean;
		highlightMessageId?: string | null;
		threadLookup?: (messageId: string) => Thread | undefined;
		onLoadMore?: () => void;
		onStartEdit?: (id: string, content: string) => void;
		onCancelEdit?: () => void;
		onSaveEdit?: (id: string, content: string) => void;
		onDelete?: (id: string) => void;
		onStartThread?: (messageId: string) => void;
		onOpenThread?: (thread: Thread) => void;
	} = $props();

	let scrollContainer = $state<HTMLDivElement | null>(null);
	let editInput = $state<HTMLTextAreaElement | null>(null);
	let shouldAutoScroll = $state(true);
	let localEditContent = $state('');
	let scrolledToHighlight = $state<string | null>(null);

	// Track when editingId changes to sync local content
	$effect(() => {
		if (editingId) {
			localEditContent = editContent;
			tick().then(() => editInput?.focus());
		}
	});

	// Scroll to highlighted message when it appears
	$effect(() => {
		if (highlightMessageId && messages.length > 0 && scrollContainer && scrolledToHighlight !== highlightMessageId) {
			tick().then(() => {
				const el = scrollContainer?.querySelector(`[data-message-id="${highlightMessageId}"]`);
				if (el) {
					el.scrollIntoView({ behavior: 'smooth', block: 'center' });
					scrolledToHighlight = highlightMessageId;
				}
			});
		}
	});

	// Auto-scroll on new messages
	$effect(() => {
		if (messages.length > 0 && shouldAutoScroll && !highlightMessageId && scrollContainer) {
			tick().then(() => {
				if (scrollContainer) {
					scrollContainer.scrollTop = scrollContainer.scrollHeight;
				}
			});
		}
	});

	function handleScroll() {
		if (!scrollContainer) return;
		const { scrollTop, scrollHeight, clientHeight } = scrollContainer;

		// Auto-scroll if near bottom
		shouldAutoScroll = scrollHeight - scrollTop - clientHeight < 100;

		// Load more at top
		if (scrollTop < 50 && canLoadMore && !loading) {
			onLoadMore?.();
		}
	}

	function isNewGroup(msg: Message, prev: Message | undefined): boolean {
		if (!prev) return true;
		if (msg.author_id !== prev.author_id) return true;
		// Group messages within 5 minutes
		const diff = new Date(msg.created_at + 'Z').getTime() - new Date(prev.created_at + 'Z').getTime();
		return diff > 300_000;
	}

	function handleEditKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			if (editingId && localEditContent.trim()) {
				onSaveEdit?.(editingId, localEditContent);
			}
		}
		if (e.key === 'Escape') {
			onCancelEdit?.();
		}
	}

	let lightboxUrl = $state<string | null>(null);
	let lightboxName = $state('');

	function openLightbox(url: string, name: string) {
		lightboxUrl = url;
		lightboxName = name;
	}

	function closeLightbox() {
		lightboxUrl = null;
	}

	function handleLightboxKey(e: KeyboardEvent) {
		if (e.key === 'Escape') closeLightbox();
	}

	function isImage(mime: string): boolean {
		return mime.startsWith('image/');
	}

	function formatFileSize(bytes: number): string {
		if (bytes < 1024) return bytes + ' B';
		if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
		return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
	}

	let emojiPickerMessageId = $state<string | null>(null);
	let pickerPos = $state<{ x: number; y: number } | null>(null);

	function toggleReaction(messageId: string, emoji: string, hasReacted: boolean) {
		if (hasReacted) {
			removeReaction(messageId, emoji).catch(() => {});
		} else {
			addReaction(messageId, emoji).catch(() => {});
		}
	}

	const PICKER_W = 320;
	const PICKER_H = 360;

	function openEmojiPicker(messageId: string, e?: MouseEvent) {
		if (emojiPickerMessageId === messageId) {
			emojiPickerMessageId = null;
			pickerPos = null;
			return;
		}
		emojiPickerMessageId = messageId;
		if (e) {
			const btn = e.currentTarget as HTMLElement;
			const rect = btn.getBoundingClientRect();
			let x = rect.left;
			let y = rect.bottom + 4;
			// clamp to viewport
			if (x + PICKER_W > window.innerWidth) x = window.innerWidth - PICKER_W - 8;
			if (x < 8) x = 8;
			if (y + PICKER_H > window.innerHeight) y = rect.top - PICKER_H - 4;
			pickerPos = { x, y };
		}
	}

	function handlePickerSelect(messageId: string, emoji: string) {
		addReaction(messageId, emoji).catch(() => {});
	}

	function closePicker() {
		emojiPickerMessageId = null;
		pickerPos = null;
	}

	const GIF_MSG_RE = /^!\[GIF\]\((\/api\/v0\/media\/proxy\?url=.+)\)$/;

	function isGifMessage(content: string): boolean {
		return GIF_MSG_RE.test(content.trim());
	}

	function gifUrl(content: string): string {
		const m = content.trim().match(GIF_MSG_RE);
		return m ? m[1] : '';
	}

	function typingText(users: string[]): string {
		if (users.length === 0) return '';
		if (users.length === 1) return `${users[0]} is typing...`;
		if (users.length === 2) return `${users[0]} and ${users[1]} are typing...`;
		return `${users[0]} and ${users.length - 1} others are typing...`;
	}
</script>

<div class="message-list-container">
	<div class="message-list" bind:this={scrollContainer} onscroll={handleScroll}>
		{#if loading && messages.length === 0}
			<div class="loading">Loading messages...</div>
		{/if}

		{#if canLoadMore && messages.length > 0}
			<div class="load-more">
				{#if loading}
					<span>Loading...</span>
				{/if}
			</div>
		{/if}

	{#each messages as msg, i}
			{@const newGroup = isNewGroup(msg, messages[i - 1])}
			{@const msgThread = threadLookup?.(msg.id)}
		<div class="message" class:grouped={!newGroup} class:editing={editingId === msg.id} class:highlighted={highlightMessageId === msg.id} data-message-id={msg.id}>
				{#if newGroup}
					<div class="message-header">
						<span class="author">{msg.author_display_name || msg.author_username || 'Unknown'}</span>
						<span class="timestamp">{formatTimestamp(msg.created_at)}</span>
					</div>
				{/if}
				<div class="message-body">
					{#if editingId === msg.id}
						<textarea
							class="edit-input"
							bind:this={editInput}
							bind:value={localEditContent}
							onkeydown={handleEditKeydown}
							rows="2"
						></textarea>
						<div class="edit-actions">
							<button class="btn-sm" onclick={() => onCancelEdit?.()}>Cancel</button>
							<button class="btn-sm btn-primary" onclick={() => editingId && onSaveEdit?.(editingId, localEditContent)}>Save</button>
						</div>
					{:else}
						{#if isGifMessage(msg.content)}
							<div class="gif-message">
								<img src={gifUrl(msg.content)} alt="GIF" class="gif-image" />
								<img src={klipyLogo} alt="KLIPY" class="klipy-watermark" />
							</div>
						{:else}
							<div class="content">{@html renderMarkdown(msg.content)}</div>
						{/if}
						{#if msg.attachments && msg.attachments.length > 0}
							<div class="attachments">
								{#each msg.attachments as att}
									{#if isImage(att.mime_type)}
										<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
										<img
											src={att.url}
											alt={att.original_name}
											class="attachment-image"
											onclick={() => openLightbox(att.url, att.original_name)}
											onkeydown={(e) => e.key === 'Enter' && openLightbox(att.url, att.original_name)}
										/>
									{:else}
										<a href={att.url} class="attachment-file" download={att.original_name}>
											<span class="file-icon">📄</span>
											<span class="file-name">{att.original_name}</span>
											<span class="file-size">{formatFileSize(att.size_bytes)}</span>
										</a>
									{/if}
								{/each}
							</div>
						{/if}
						{#if msg.edited_at}
							<span class="edited">(edited)</span>
						{/if}
					{/if}
				</div>
				{#if (msg.reactions && msg.reactions.length > 0) || emojiPickerMessageId === msg.id}
					<div class="reactions">
						{#each msg.reactions ?? [] as reaction}
							<button
								class="reaction-pill"
								class:me={reaction.me}
								onclick={() => toggleReaction(msg.id, reaction.emoji, reaction.me)}
								title={reaction.users.join(', ')}
							>
								<span class="reaction-emoji">{reaction.emoji}</span>
								<span class="reaction-count">{reaction.count}</span>
							</button>
						{/each}
				{#if canAddReactions}
							<button class="reaction-pill add-reaction" onclick={(e) => openEmojiPicker(msg.id, e)} title="Add Reaction">+</button>
						{/if}
					</div>
				{/if}
		{#if msgThread}
				<button class="thread-indicator" onclick={() => onOpenThread?.(msgThread)}>
					<span class="thread-reply-count">{msgThread.reply_count ?? 0} {(msgThread.reply_count ?? 0) === 1 ? 'reply' : 'replies'}</span>
					<span class="thread-activity">{formatTimestamp(msgThread.last_activity_at)}</span>
					{#if (msgThread.locked ?? 0) !== 0}<span class="thread-lock">🔒</span>{/if}
				</button>
			{/if}
		{#if editingId !== msg.id}
				<div class="message-actions">
					{#if canAddReactions}
						<button class="action-btn" onclick={(e) => openEmojiPicker(msg.id, e)} title="React">😀</button>
					{/if}
					{#if canCreateThreads && !msgThread && !msg.thread_id}
						<button class="action-btn" onclick={() => onStartThread?.(msg.id)} title="Start Thread">💬</button>
					{/if}
					{#if (msg.author_id === currentUserId && canManageOwnMessages) || canManageMessages}
						<button class="action-btn" onclick={() => onStartEdit?.(msg.id, msg.content)} title="Edit">✏️</button>
						<button class="action-btn" onclick={() => onDelete?.(msg.id)} title="Delete">🗑️</button>
					{/if}
				</div>
			{/if}
			</div>
		{/each}
	</div>

	{#if typingUsers.length > 0}
		<div class="typing-indicator">{typingText(typingUsers)}</div>
	{/if}
</div>

{#if emojiPickerMessageId && pickerPos}
	<div class="emoji-picker-fixed" style="left: {pickerPos.x}px; top: {pickerPos.y}px;">
		<EmojiPicker onSelect={(emoji) => { handlePickerSelect(emojiPickerMessageId!, emoji); closePicker(); }} onClose={closePicker} />
	</div>
{/if}

{#if lightboxUrl}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="lightbox" onclick={closeLightbox} onkeydown={handleLightboxKey}>
		<button class="lightbox-close" onclick={closeLightbox}>✕</button>
		<img src={lightboxUrl} alt={lightboxName} class="lightbox-img" />
	</div>
{/if}

<style>
	.message-list-container {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-height: 0;
	}

	.message-list {
		flex: 1;
		overflow-y: auto;
		padding: 1rem;
		display: flex;
		flex-direction: column;
	}

	.loading {
		text-align: center;
		color: var(--text-muted);
		padding: 2rem;
	}

	.load-more {
		text-align: center;
		padding: 0.5rem;
		color: var(--text-muted);
		font-size: 0.8rem;
	}

	.message {
		padding: 0.25rem 0.5rem;
		border-radius: 4px;
		position: relative;
	}

	.message:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.02));
	}

	.message:not(.grouped) {
		margin-top: 1rem;
	}

	.message.grouped {
		margin-top: 0;
	}

	.message.editing {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.message.highlighted {
		animation: highlight-fade 3s ease-out;
	}

	@keyframes highlight-fade {
		0%, 30% {
			background: rgba(233, 69, 96, 0.2);
		}
		100% {
			background: transparent;
		}
	}

	.message-header {
		display: flex;
		align-items: baseline;
		gap: 0.5rem;
		margin-bottom: 0.15rem;
	}

	.author {
		font-weight: 600;
		font-size: 0.9rem;
	}

	.timestamp {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.message-body {
		font-size: 0.9rem;
		line-height: 1.4;
	}

	.message-body :global(p) {
		margin: 0 0 0.25rem;
	}

	.message-body :global(p:last-child) {
		margin-bottom: 0;
	}

	.message-body :global(code) {
		background: var(--bg-surface);
		padding: 0.1rem 0.3rem;
		border-radius: 3px;
		font-size: 0.85em;
	}

	.message-body :global(pre) {
		background: var(--bg-surface);
		padding: 0.75rem;
		border-radius: 6px;
		overflow-x: auto;
		margin: 0.25rem 0;
	}

	.message-body :global(pre code) {
		background: none;
		padding: 0;
	}

	.message-body :global(blockquote) {
		border-left: 3px solid var(--border);
		margin: 0.25rem 0;
		padding-left: 0.75rem;
		color: var(--text-muted);
	}

	.message-body :global(a) {
		color: var(--accent, #5865f2);
	}

	.edited {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.edit-input {
		width: 100%;
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.5rem;
		font-size: 0.9rem;
		font-family: inherit;
		resize: vertical;
	}

	.edit-actions {
		display: flex;
		gap: 0.5rem;
		margin-top: 0.25rem;
	}

	.btn-sm {
		padding: 0.2rem 0.5rem;
		font-size: 0.75rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 3px;
		cursor: pointer;
	}

	.btn-sm.btn-primary {
		background: var(--accent, #5865f2);
		color: white;
		border-color: var(--accent, #5865f2);
	}

	.message-actions {
		position: absolute;
		top: 0;
		right: 0.5rem;
		display: none;
		gap: 0.25rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 4px;
		padding: 0.15rem;
	}

	.message:hover .message-actions {
		display: flex;
	}

	.action-btn {
		background: none;
		border: none;
		cursor: pointer;
		padding: 0.15rem 0.3rem;
		font-size: 0.75rem;
		border-radius: 3px;
	}

	.action-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.1));
	}

	.thread-indicator {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		padding: 0.25rem 0.5rem;
		margin-top: 0.25rem;
		background: none;
		border: none;
		color: var(--accent, #5865f2);
		font-size: 0.75rem;
		cursor: pointer;
		border-radius: 4px;
	}

	.thread-indicator:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.thread-reply-count {
		font-weight: 600;
	}

	.thread-activity {
		color: var(--text-muted);
	}

	.thread-lock {
		font-size: 0.65rem;
	}

	.reactions {
		display: flex;
		flex-wrap: wrap;
		gap: 0.25rem;
		margin-top: 0.25rem;
	}

	.reaction-pill {
		display: inline-flex;
		align-items: center;
		gap: 0.25rem;
		padding: 0.15rem 0.5rem;
		border-radius: 999px;
		border: 1px solid var(--border);
		background: var(--bg-surface);
		color: var(--text);
		font-size: 0.8rem;
		cursor: pointer;
		line-height: 1.3;
	}

	.reaction-pill:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.08));
	}

	.reaction-pill.me {
		border-color: var(--accent, #5865f2);
		background: rgba(88, 101, 242, 0.15);
	}

	.reaction-emoji {
		font-size: 0.9rem;
	}

	.reaction-count {
		font-size: 0.75rem;
		color: var(--text-muted);
	}

	.reaction-pill.me .reaction-count {
		color: var(--accent, #5865f2);
	}

	.reaction-pill.add-reaction {
		color: var(--text-muted);
		font-size: 0.85rem;
		padding: 0.15rem 0.4rem;
	}

	.emoji-picker-fixed {
		position: fixed;
		z-index: 100;
	}

	.typing-indicator {
		padding: 0.25rem 1rem;
		font-size: 0.75rem;
		color: var(--text-muted);
		min-height: 1.5rem;
	}

	.attachments {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
		margin-top: 0.25rem;
	}

	.gif-message {
		position: relative;
		display: inline-block;
		margin-top: 0.25rem;
	}

	.gif-image {
		max-width: 400px;
		max-height: 300px;
		border-radius: 6px;
		object-fit: contain;
		display: block;
	}

	.klipy-watermark {
		position: absolute;
		bottom: 6px;
		left: 6px;
		height: 16px;
		width: auto;
		opacity: 0.7;
		pointer-events: none;
	}

	.attachment-image {
		max-width: 400px;
		max-height: 300px;
		border-radius: 6px;
		cursor: pointer;
		object-fit: contain;
	}

	.attachment-image:hover {
		opacity: 0.9;
	}

	.attachment-file {
		display: inline-flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 6px;
		padding: 0.5rem 0.75rem;
		text-decoration: none;
		color: var(--text);
		max-width: 300px;
	}

	.attachment-file:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.file-icon {
		font-size: 1.25rem;
	}

	.file-name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		font-size: 0.85rem;
	}

	.file-size {
		color: var(--text-muted);
		font-size: 0.75rem;
		white-space: nowrap;
	}

	.lightbox {
		position: fixed;
		top: 0;
		left: 0;
		right: 0;
		bottom: 0;
		background: rgba(0, 0, 0, 0.85);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 1000;
	}

	.lightbox-img {
		max-width: 90vw;
		max-height: 90vh;
		object-fit: contain;
		border-radius: 4px;
	}

	.lightbox-close {
		position: absolute;
		top: 1rem;
		right: 1rem;
		background: none;
		border: none;
		color: white;
		font-size: 1.5rem;
		cursor: pointer;
		padding: 0.5rem;
	}
</style>
