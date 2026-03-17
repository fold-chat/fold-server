<script lang="ts">
import type { Message } from '$lib/api/messages.js';
	import type { Thread } from '$lib/api/threads.js';
	import { addReaction, removeReaction } from '$lib/api/reactions.js';
import { renderMarkdown, formatTimestamp, isEmojiOnly, extractYouTubeVideoIds, applyRoleColors } from '$lib/utils/markdown.js';
	import { getYoutubeEmbedEnabled } from '$lib/stores/auth.svelte.js';
	import YouTubeEmbed from './YouTubeEmbed.svelte';
	import { openMemberProfile } from '$lib/stores/membersPanel.svelte.js';
	import { findCustomEmojiByName } from '$lib/stores/emoji.svelte.js';
	import EmojiPicker from './EmojiPicker.svelte';
	import CollapsibleContent from './CollapsibleContent.svelte';
	import klipyLogo from '$lib/assets/klipy.svg';
	import { tick } from 'svelte';

	const CUSTOM_EMOJI_RE = /^:([a-zA-Z0-9_]{2,32}):$/;

	function getCustomEmojiUrl(emoji: string): string | null {
		const match = emoji.match(CUSTOM_EMOJI_RE);
		if (!match) return null;
		const ce = findCustomEmojiByName(match[1].toLowerCase());
		return ce?.url ?? null;
	}

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
		threadMode = false,
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
		threadMode?: boolean;
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
				requestAnimationFrame(() => {
					if (scrollContainer) {
						scrollContainer.scrollTop = scrollContainer.scrollHeight;
					}
				});
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

	let lightboxImages = $state<{ url: string; name: string }[]>([]);
	let lightboxIndex = $state(0);
	let lightboxUrl = $derived(lightboxImages.length > 0 ? lightboxImages[lightboxIndex]?.url ?? null : null);
	let lightboxName = $derived(lightboxImages.length > 0 ? lightboxImages[lightboxIndex]?.name ?? '' : '');

	function openLightbox(images: { url: string; name: string }[], index: number) {
		lightboxImages = images;
		lightboxIndex = index;
	}

	function closeLightbox() {
		lightboxImages = [];
		lightboxIndex = 0;
	}

	function handleLightboxKey(e: KeyboardEvent) {
		if (e.key === 'Escape') closeLightbox();
		if (e.key === 'ArrowRight' && lightboxIndex < lightboxImages.length - 1) {
			e.stopPropagation();
			lightboxIndex++;
		}
		if (e.key === 'ArrowLeft' && lightboxIndex > 0) {
			e.stopPropagation();
			lightboxIndex--;
		}
	}

	function autoFocus(node: HTMLElement) {
		node.focus();
	}

	function isImage(mime: string): boolean {
		return mime.startsWith('image/') && mime !== 'image/svg+xml';
	}

	function isVideo(mime: string): boolean {
		return mime.startsWith('video/');
	}

	function formatDuration(seconds: number): string {
		const m = Math.floor(seconds / 60);
		const s = Math.floor(seconds % 60);
		return `${m}:${s.toString().padStart(2, '0')}`;
	}

	let playingVideos = $state<Set<string>>(new Set());

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

	function handleContentClick(e: MouseEvent) {
		const target = e.target as HTMLElement;
		const mention = target.closest('.mention[data-user-id]') as HTMLElement | null;
		if (mention) {
			e.preventDefault();
			const userId = mention.dataset.userId;
			if (userId) openMemberProfile(userId);
		}
	}

	function scrollToBottomIfNeeded() {
		if (shouldAutoScroll && scrollContainer) {
			scrollContainer.scrollTop = scrollContainer.scrollHeight;
		}
	}

	function typingText(users: string[]): string {
		if (users.length === 0) return '';
		if (users.length === 1) return `${users[0]} is typing...`;
		if (users.length === 2) return `${users[0]} and ${users[1]} are typing...`;
		return `${users[0]} and ${users.length - 1} others are typing...`;
	}
</script>

<div class="message-list-container" class:thread-mode={threadMode}>
	<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events -->
	<div class="message-list" bind:this={scrollContainer} onscroll={handleScroll} onclick={handleContentClick}>
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
			{@const newGroup = threadMode ? true : isNewGroup(msg, messages[i - 1])}
			{@const msgThread = threadLookup?.(msg.id)}
		<div class="message" class:grouped={!newGroup} class:editing={editingId === msg.id} class:highlighted={highlightMessageId === msg.id} class:thread-msg={threadMode} data-message-id={msg.id}>
				{#if newGroup}
					<div class="message-header">
						{#if msg.author_avatar_url}
							<img class="msg-avatar" src={msg.author_avatar_url} alt="" />
						{:else}
							<div class="msg-avatar msg-avatar-fallback">{(msg.author_display_name || msg.author_username || '?')[0].toUpperCase()}</div>
						{/if}
						<button class="author" onclick={() => openMemberProfile(msg.author_id)}>{ msg.author_display_name || msg.author_username || 'Unknown'}</button>
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
								<img src={gifUrl(msg.content)} alt="GIF" class="gif-image" onload={scrollToBottomIfNeeded} onerror={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; (e.currentTarget as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }} />
								<div class="image-broken hidden">
									<span class="image-broken-icon">🖼️</span>
									<span class="image-broken-text">Image could not be loaded</span>
								</div>
								<img src={klipyLogo} alt="KLIPY" class="klipy-watermark" />
							</div>
						{:else}
							<CollapsibleContent>
							<div class="content" class:emoji-only={isEmojiOnly(msg.content)} use:applyRoleColors>{@html renderMarkdown(msg.content, { mentions: msg.mentions, mention_roles: msg.mention_roles, mention_everyone: msg.mention_everyone })}</div>
								{#if getYoutubeEmbedEnabled()}
									{#each extractYouTubeVideoIds(msg.content) as videoId}
										<YouTubeEmbed {videoId} />
									{/each}
								{/if}
								{#if msg.attachments && msg.attachments.length > 0}
									{@const imageAtts = msg.attachments.filter(a => isImage(a.mime_type))}
									{@const videoAtts = msg.attachments.filter(a => isVideo(a.mime_type))}
									{@const fileAtts = msg.attachments.filter(a => !isImage(a.mime_type) && !isVideo(a.mime_type))}
									{#if imageAtts.length > 0}
										{@const lbImages = imageAtts.map(a => ({ url: a.url, name: a.original_name }))}
										<div class="image-attachments" class:multi={imageAtts.length > 1}>
											{#each imageAtts as att, idx}
												<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
												<div class="attachment-image-wrapper">
													<img
														src={att.thumbnail_url ?? att.url}
														alt={att.original_name}
														class="attachment-image"
														onclick={() => openLightbox(lbImages, idx)}
														onkeydown={(e) => e.key === 'Enter' && openLightbox(lbImages, idx)}
														onload={scrollToBottomIfNeeded}
														onerror={(e) => { (e.currentTarget as HTMLImageElement).style.display = 'none'; (e.currentTarget as HTMLImageElement).nextElementSibling?.classList.remove('hidden'); }}
													/>
													<div class="image-broken hidden">
														<span class="image-broken-icon">🖼️</span>
														<span class="image-broken-text">{att.original_name ?? 'Image'} could not be loaded</span>
													</div>
												</div>
											{/each}
										</div>
									{/if}
									{#if videoAtts.length > 0}
										<div class="video-attachments">
											{#each videoAtts as att}
												{#if att.processing_status === 'processing'}
													<div class="video-processing">
														<span class="processing-spinner">⏳</span>
														<span>Processing video...</span>
													</div>
												{:else if att.processing_status === 'failed'}
													<div class="video-failed">
														<span>⚠️ Video processing failed</span>
														<a href={att.url} download={att.original_name} class="download-link">Download original</a>
													</div>
												{:else if playingVideos.has(att.id)}
													<!-- svelte-ignore a11y_media_has_caption -->
													<video class="attachment-video" controls autoplay onloadeddata={scrollToBottomIfNeeded}
														onerror={(e) => { (e.currentTarget as HTMLVideoElement).style.display = 'none'; (e.currentTarget as HTMLVideoElement).nextElementSibling?.classList.remove('hidden'); }}
													>
														<source src={att.url} />
													</video>
													<div class="video-error hidden">
														<span>⚠️ Your browser can't play this video</span>
														<a href={att.url} download={att.original_name} class="download-link">Download</a>
													</div>
												{:else}
													<!-- svelte-ignore a11y_no_static_element_interactions -->
													<div class="video-thumbnail" onclick={() => { playingVideos = new Set([...playingVideos, att.id]); }} onkeydown={(e) => e.key === 'Enter' && (playingVideos = new Set([...playingVideos, att.id]))}>
														{#if att.thumbnail_url}
															<img src={att.thumbnail_url} alt={att.original_name} class="video-thumb-img" />
														{:else}
															<div class="video-thumb-placeholder">🎬</div>
														{/if}
														<div class="video-play-overlay">▶</div>
														{#if att.duration_seconds}
															<span class="video-duration">{formatDuration(att.duration_seconds)}</span>
														{/if}
													</div>
												{/if}
											{/each}
										</div>
									{/if}
									{#if fileAtts.length > 0}
										<div class="attachments">
											{#each fileAtts as att}
												<a href={att.url} class="attachment-file" download={att.original_name}>
													<span class="file-icon">📄</span>
													<span class="file-name">{att.original_name}</span>
													<span class="file-size">{formatFileSize(att.size_bytes)}</span>
												</a>
											{/each}
										</div>
									{/if}
								{/if}
								{#if msg.edited_at}
									<span class="edited">(edited)</span>
								{/if}
							</CollapsibleContent>
						{/if}
					{/if}
				</div>
				{#if (msg.reactions && msg.reactions.length > 0) || emojiPickerMessageId === msg.id}
					<div class="reactions">
						{#each msg.reactions ?? [] as reaction}
							{@const customUrl = getCustomEmojiUrl(reaction.emoji)}
							<button
								class="reaction-pill"
								class:me={reaction.me}
								onclick={() => toggleReaction(msg.id, reaction.emoji, reaction.me)}
								title={reaction.users.join(', ')}
							>
								{#if customUrl}
									<img src={customUrl} alt={reaction.emoji} class="reaction-custom-emoji" />
								{:else}
									<span class="reaction-emoji">{reaction.emoji}</span>
								{/if}
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
					{#if !msgThread || canManageMessages}
						<button class="action-btn" onclick={() => onDelete?.(msg.id)} title="Delete">🗑️</button>
					{/if}
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
	<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
	<div class="lightbox" tabindex="0" use:autoFocus onclick={closeLightbox} onkeydown={handleLightboxKey}>
		<button class="lightbox-close" onclick={closeLightbox}>✕</button>
		{#if lightboxImages.length > 1}
			{#if lightboxIndex > 0}
				<button class="lightbox-nav lightbox-prev" onclick={(e) => { e.stopPropagation(); lightboxIndex--; }}>‹</button>
			{/if}
		{/if}
		<!-- svelte-ignore a11y_no_noninteractive_element_interactions, a11y_click_events_have_key_events -->
		<img src={lightboxUrl} alt={lightboxName} class="lightbox-img" onclick={(e) => e.stopPropagation()} />
		{#if lightboxImages.length > 1}
			{#if lightboxIndex < lightboxImages.length - 1}
				<button class="lightbox-nav lightbox-next" onclick={(e) => { e.stopPropagation(); lightboxIndex++; }}>›</button>
			{/if}
			<div class="lightbox-counter">{lightboxIndex + 1} / {lightboxImages.length}</div>
		{/if}
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
		padding: var(--msg-padding);
		border-radius: 4px;
		position: relative;
	}

	.message:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.02));
	}

	.message:not(.grouped) {
		margin-top: var(--msg-gap);
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
		align-items: center;
		gap: 0.5rem;
		margin-bottom: 0.15rem;
	}

	.author {
		font-weight: 600;
		font-size: var(--msg-font);
		background: none;
		border: none;
		padding: 0;
		color: var(--text);
		cursor: pointer;
		font-family: inherit;
	}

	.author:hover {
		text-decoration: underline;
	}

	.timestamp {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.message-body {
		font-size: var(--msg-font);
		line-height: var(--msg-line-height);
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

	.message-body :global(.custom-emoji) {
		width: 1.75em;
		height: 1.75em;
		object-fit: contain;
		vertical-align: middle;
		margin: 0 0.05em;
	}

	.message-body :global(.unicode-emoji) {
		font-size: 1.4em;
		line-height: 1;
		vertical-align: middle;
	}

	.content.emoji-only {
		font-size: 2.5rem;
		line-height: 1.2;
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
		font-size: 1.15rem;
	}

	.reaction-custom-emoji {
		width: 22px;
		height: 22px;
		object-fit: contain;
		vertical-align: middle;
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

	.image-attachments {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin-top: 0.25rem;
	}

	.image-attachments.multi .attachment-image {
		max-width: 150px;
		max-height: 150px;
		object-fit: cover;
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

	.attachment-image-wrapper {
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

	.video-attachments {
		display: flex;
		flex-wrap: wrap;
		gap: 0.5rem;
		margin-top: 0.25rem;
	}

	.video-thumbnail {
		position: relative;
		cursor: pointer;
		border-radius: 6px;
		overflow: hidden;
		max-width: 400px;
		max-height: 300px;
	}

	.video-thumb-img {
		max-width: 400px;
		max-height: 300px;
		object-fit: contain;
		border-radius: 6px;
		display: block;
	}

	.video-thumb-placeholder {
		width: 200px;
		height: 120px;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 6px;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 2rem;
	}

	.video-play-overlay {
		position: absolute;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		background: rgba(0, 0, 0, 0.6);
		color: white;
		width: 48px;
		height: 48px;
		border-radius: 50%;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 1.25rem;
		pointer-events: none;
	}

	.video-thumbnail:hover .video-play-overlay {
		background: rgba(0, 0, 0, 0.8);
	}

	.video-duration {
		position: absolute;
		bottom: 6px;
		right: 6px;
		background: rgba(0, 0, 0, 0.7);
		color: white;
		font-size: 0.7rem;
		padding: 0.1rem 0.4rem;
		border-radius: 3px;
	}

	.attachment-video {
		max-width: 400px;
		max-height: 300px;
		border-radius: 6px;
	}

	.video-processing, .video-failed {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		border-radius: 6px;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text-muted);
		font-size: 0.85rem;
	}

	.video-error {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		border-radius: 6px;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text-muted);
		font-size: 0.85rem;
	}

	.video-error.hidden {
		display: none;
	}

	.processing-spinner {
		animation: spin 1.5s linear infinite;
	}

	@keyframes spin {
		from { transform: rotate(0deg); }
		to { transform: rotate(360deg); }
	}

	.download-link {
		color: var(--accent, #5865f2);
		text-decoration: underline;
		font-size: 0.8rem;
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
		outline: none;
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

	.lightbox-nav {
		position: absolute;
		top: 50%;
		transform: translateY(-50%);
		background: rgba(0, 0, 0, 0.5);
		border: none;
		color: white;
		font-size: 2.5rem;
		cursor: pointer;
		padding: 0.5rem 0.75rem;
		border-radius: 4px;
		line-height: 1;
	}

	.lightbox-nav:hover {
		background: rgba(0, 0, 0, 0.7);
	}

	.lightbox-prev {
		left: 1rem;
	}

	.lightbox-next {
		right: 1rem;
	}

	.lightbox-counter {
		position: absolute;
		bottom: 1rem;
		left: 50%;
		transform: translateX(-50%);
		color: rgba(255, 255, 255, 0.7);
		font-size: 0.85rem;
	}

	/* ── Thread mode ── */
	.message-list-container.thread-mode .message-list {
		gap: 0.5rem;
		padding: 1rem 1.5rem;
	}

	.message.thread-msg {
		background: var(--bg-surface, rgba(255, 255, 255, 0.02));
		border: 1px solid var(--border);
		border-radius: 10px;
		padding: 0.75rem 1rem;
	}

	.message.thread-msg:not(.grouped) {
		margin-top: 0;
	}

	.message.thread-msg:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.04));
	}

	.message.thread-msg .message-header {
		align-items: center;
	}

	.msg-avatar {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.msg-avatar-fallback {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--accent, #5865f2);
		color: white;
		font-size: 0.75rem;
		font-weight: 600;
	}

	.message.thread-msg .message-actions {
		top: 0.5rem;
		right: 0.75rem;
	}
</style>
