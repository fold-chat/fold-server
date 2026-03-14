<script lang="ts">
	import { uploadFile } from '$lib/api/messages.js';
	import EmojiPicker from './EmojiPicker.svelte';
	import GifPicker from './GifPicker.svelte';
	import MentionAutocomplete from './MentionAutocomplete.svelte';
	import { getMediaSearchEnabled, hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { getMembers } from '$lib/stores/members.svelte.js';
	import { getRolesList } from '$lib/stores/roles.svelte.js';

	interface PendingFile {
		file: File;
		id?: string;
		url?: string;
		uploading: boolean;
		error?: string;
		preview?: string;
	}

	let { onSend, onTyping, disabled = false, canUploadFiles = true, channelId = null, forumMode = false }: { onSend: (content: string, attachmentIds?: string[]) => Promise<void> | void; onTyping?: () => void; disabled?: boolean; canUploadFiles?: boolean; channelId?: string | null; forumMode?: boolean } = $props();

	let content = $state('');
	let textarea = $state<HTMLTextAreaElement | null>(null);
	let fileInput = $state<HTMLInputElement | null>(null);
	let pendingFiles = $state<PendingFile[]>([]);
	let dragging = $state(false);
	let showEmojiPicker = $state(false);
	let showGifPicker = $state(false);
	let mentionQuery = $state<string | null>(null);
	let mentionStartPos = $state<number>(0);
	let mentionSelectedIndex = $state(0);
	let rateLimitSeconds = $state(0);
	let rateLimitTimer: ReturnType<typeof setInterval> | null = null;
	let rateLimited = $derived(rateLimitSeconds > 0);
	let canSend = $derived((content.trim().length > 0 || pendingFiles.length > 0) && !rateLimited);
	let showMentionEveryone = $derived(channelId ? hasChannelPermission(channelId, 'MENTION_EVERYONE') : false);

	interface MentionItem {
		type: 'user' | 'role' | 'everyone';
		id?: string;
		name: string;
		displayName?: string;
		color?: string;
	}

	let mentionItems = $derived.by(() => {
		if (mentionQuery === null) return [] as MentionItem[];
		const q = mentionQuery.toLowerCase();
		const items: MentionItem[] = [];
		for (const member of getMembers()) {
			const username = member.username.toLowerCase();
			const displayName = (member.display_name || member.username).toLowerCase();
			if (username.includes(q) || displayName.includes(q)) {
				items.push({ type: 'user', id: member.id, name: member.username, displayName: member.display_name || member.username });
			}
		}
		for (const role of getRolesList()) {
			if (role.name.toLowerCase().includes(q)) {
				items.push({ type: 'role', id: role.id, name: role.name, color: role.color ?? undefined });
			}
		}
		if (showMentionEveryone && 'everyone'.includes(q)) {
			items.push({ type: 'everyone', name: 'everyone' });
		}
		return items.slice(0, 10);
	});

	function handleKeydown(e: KeyboardEvent) {
		if (mentionQuery !== null && mentionItems.length > 0) {
			if (e.key === 'Escape') {
				e.preventDefault();
				mentionQuery = null;
				return;
			}
			if (e.key === 'ArrowUp') {
				e.preventDefault();
				mentionSelectedIndex = Math.max(0, mentionSelectedIndex - 1);
				return;
			}
			if (e.key === 'ArrowDown') {
				e.preventDefault();
				mentionSelectedIndex = Math.min(mentionItems.length - 1, mentionSelectedIndex + 1);
				return;
			}
			if (e.key === 'Enter' || e.key === 'Tab') {
				e.preventDefault();
				const item = mentionItems[mentionSelectedIndex];
				if (item) insertMention(item);
				return;
			}
		}
		
	if (e.key === 'Enter') {
			if (forumMode) {
				if (e.ctrlKey || e.metaKey) {
					e.preventDefault();
					submit();
				}
			} else if (!e.shiftKey) {
				e.preventDefault();
				submit();
			}
		}
	}

	function handleInput() {
		onTyping?.();
		autoResize();
		detectMention();
	}

	function detectMention() {
		if (!textarea) return;
		const pos = textarea.selectionStart;
		const textBefore = content.slice(0, pos);
		
		// Find last '@' before cursor
		const lastAt = textBefore.lastIndexOf('@');
		if (lastAt === -1) {
			mentionQuery = null;
			return;
		}
		
		// Check if there's whitespace between @ and cursor
		const afterAt = textBefore.slice(lastAt + 1);
		if (/\s/.test(afterAt)) {
			mentionQuery = null;
			return;
		}
		
		// Valid mention trigger
		mentionQuery = afterAt;
		mentionStartPos = lastAt;
		mentionSelectedIndex = 0;
	}

	function insertMention(item: { type: 'user' | 'role' | 'everyone'; id?: string; name: string }) {
		if (!textarea || mentionQuery === null) return;
		
		let insertText = '';
		if (item.type === 'user' && item.id) {
			insertText = `<@${item.id}>`;
		} else if (item.type === 'role' && item.id) {
			insertText = `<@&${item.id}>`;
		} else if (item.type === 'everyone') {
			insertText = '@everyone';
		}
		
		// Replace from @ to cursor position
		const before = content.slice(0, mentionStartPos);
		const after = content.slice(textarea.selectionStart);
		content = before + insertText + ' ' + after;
		
		// Reset mention state
		mentionQuery = null;
		
		// Move cursor after inserted mention
		const newPos = before.length + insertText.length + 1;
		requestAnimationFrame(() => {
			textarea?.focus();
			textarea?.setSelectionRange(newPos, newPos);
		});
	}

	function startRateLimit(seconds: number) {
		rateLimitSeconds = Math.ceil(seconds);
		if (rateLimitTimer) clearInterval(rateLimitTimer);
		rateLimitTimer = setInterval(() => {
			rateLimitSeconds--;
			if (rateLimitSeconds <= 0) {
				if (rateLimitTimer) { clearInterval(rateLimitTimer); rateLimitTimer = null; }
			}
		}, 1000);
	}

	async function submit() {
		const trimmed = content.trim();
		const uploading = pendingFiles.some(f => f.uploading);
		if (!trimmed && pendingFiles.length === 0) return;
		if (uploading || rateLimited) return;

		const ids = pendingFiles.filter(f => f.id).map(f => f.id!);
		try {
			await onSend(trimmed, ids.length > 0 ? ids : undefined);
			content = '';
			pendingFiles = [];
			if (textarea) textarea.style.height = 'auto';
		} catch (err: any) {
			if (err?.status === 429) {
				startRateLimit(err.retry_after ?? 10);
			}
		}
	}

	function autoResize() {
		if (!textarea) return;
		textarea.style.height = 'auto';
		textarea.style.height = Math.min(textarea.scrollHeight, 200) + 'px';
	}

	function addFiles(files: FileList | File[]) {
		const batch: PendingFile[] = [];
		for (const file of files) {
			const pf: PendingFile = { file, uploading: true };
			if (file.type.startsWith('image/')) {
				pf.preview = URL.createObjectURL(file);
			}
			batch.push(pf);
		}
		pendingFiles = [...pendingFiles, ...batch];
		uploadSequentially(batch);
	}

	async function uploadSequentially(batch: PendingFile[]) {
		for (const pf of batch) {
			const file = pf.file;
			try {
				const result = await uploadFile(file);
				pendingFiles = pendingFiles.map(f =>
					f.file === file && f.uploading
						? { ...f, id: result.id, url: result.url, uploading: false }
						: f
				);
			} catch (err: any) {
				const msg = err?.message || err?.error || 'Upload failed';
				pendingFiles = pendingFiles.map(f =>
					f.file === file && f.uploading
						? { ...f, error: msg, uploading: false }
						: f
				);
			}
		}
	}

	function retryFile(idx: number) {
		pendingFiles = pendingFiles.map((f, i) =>
			i === idx ? { ...f, uploading: true, error: undefined } : f
		);
		uploadSequentially([pendingFiles[idx]]);
	}

	function removeFile(idx: number) {
		const pf = pendingFiles[idx];
		if (pf.preview) URL.revokeObjectURL(pf.preview);
		pendingFiles = pendingFiles.filter((_, i) => i !== idx);
	}

	function handleDragOver(e: DragEvent) {
		e.preventDefault();
		if (disabled || !canUploadFiles) return;
		dragging = true;
	}

	function handleDragLeave() {
		dragging = false;
	}

	function handleDrop(e: DragEvent) {
		e.preventDefault();
		dragging = false;
		if (disabled || !canUploadFiles) return;
		if (e.dataTransfer?.files.length) {
			addFiles(e.dataTransfer.files);
		}
	}

	function handleFileSelect(e: Event) {
		const input = e.target as HTMLInputElement;
		if (input.files?.length) {
			addFiles(input.files);
			input.value = '';
		}
	}

	function formatSize(bytes: number): string {
		if (bytes < 1024) return bytes + ' B';
		if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
		return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
	}

	function insertGif(url: string, title: string) {
		const gifContent = `![GIF](${url})`;
		onSend(gifContent);
		showGifPicker = false;
	}

	function insertEmoji(emoji: string) {
		if (!textarea) {
			content += emoji;
			return;
		}
		const start = textarea.selectionStart ?? content.length;
		const end = textarea.selectionEnd ?? content.length;
		content = content.slice(0, start) + emoji + content.slice(end);
		showEmojiPicker = false;
		// restore cursor after emoji
		const pos = start + emoji.length;
		requestAnimationFrame(() => {
			textarea?.focus();
			textarea?.setSelectionRange(pos, pos);
		});
	}

	$effect(() => {
		function onToggleEmoji() {
			if (!disabled) showEmojiPicker = !showEmojiPicker;
		}
		window.addEventListener('fold:toggle-emoji', onToggleEmoji);
		return () => window.removeEventListener('fold:toggle-emoji', onToggleEmoji);
	});
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="compose" class:dragging ondragover={handleDragOver} ondragleave={handleDragLeave} ondrop={handleDrop}>
	{#if pendingFiles.length > 0}
		<div class="file-previews">
			{#each pendingFiles as pf, i}
				<div class="file-preview" class:error={!!pf.error}>
					{#if pf.preview}
						<img src={pf.preview} alt={pf.file.name} class="preview-thumb" />
					{:else}
						<div class="preview-icon">📄</div>
					{/if}
					<div class="preview-info">
						<span class="preview-name">{pf.file.name}</span>
						<span class="preview-size">{formatSize(pf.file.size)}</span>
					</div>
					{#if pf.uploading}
						<span class="preview-status">⏳</span>
					{:else if pf.error}
						<span class="error-text" title={pf.error}>{pf.error}</span>
						<button class="retry-btn" onclick={() => retryFile(i)} title="Retry upload">⟳</button>
					{:else}
						<span class="preview-status">✓</span>
					{/if}
					<button class="remove-btn" onclick={() => removeFile(i)} title="Remove">✕</button>
				</div>
			{/each}
		</div>
	{/if}
	<div class="compose-row">
		<button class="attach-btn" onclick={() => fileInput?.click()} title="Attach file" disabled={disabled || !canUploadFiles}>📎</button>
		<input type="file" bind:this={fileInput} onchange={handleFileSelect} multiple hidden />
		<div class="emoji-btn-wrapper">
			<button class="attach-btn" onclick={() => showEmojiPicker = !showEmojiPicker} title="Emoji" {disabled}>😀</button>
			{#if showEmojiPicker}
				<div class="emoji-picker-anchor">
					<EmojiPicker onSelect={insertEmoji} onClose={() => showEmojiPicker = false} />
				</div>
			{/if}
		</div>
		{#if getMediaSearchEnabled()}
			<div class="gif-btn-wrapper">
				<button class="attach-btn" onclick={() => showGifPicker = !showGifPicker} title="GIF" {disabled}>GIF</button>
				{#if showGifPicker}
					<div class="gif-picker-anchor">
						<GifPicker onSelect={insertGif} onClose={() => showGifPicker = false} />
					</div>
				{/if}
			</div>
		{/if}
		<div class="textarea-wrapper">
			{#if mentionQuery !== null && mentionItems.length > 0}
				<MentionAutocomplete
					items={mentionItems}
					selectedIndex={mentionSelectedIndex}
					onSelect={insertMention}
				/>
			{/if}
			{#if rateLimited}
				<div class="rate-limit-banner">Slow down! You can send again in {rateLimitSeconds}s</div>
			{/if}
		<textarea
				class="compose-input"
				class:forum-input={forumMode}
				class:rate-limited={rateLimited}
			bind:this={textarea}
			bind:value={content}
			onkeydown={handleKeydown}
			oninput={handleInput}
			placeholder={disabled ? 'You do not have permission to send messages' : forumMode ? 'Write your post… (Ctrl+Enter to submit)' : 'Send a message...'}
			rows={forumMode ? 4 : 1}
				disabled={disabled || rateLimited}
			></textarea>
		</div>
		<button class="send-btn" onclick={submit} disabled={!canSend || disabled}>Send</button>
	</div>
</div>

<style>
	.compose {
		display: flex;
		flex-direction: column;
		padding: 0.75rem 1rem;
		border-top: 1px solid var(--border);
		background: var(--bg);
		transition: border-color 0.15s;
	}

	.compose.dragging {
		border-color: var(--accent, #5865f2);
		background: rgba(88, 101, 242, 0.05);
	}

	.compose-row {
		display: flex;
		align-items: flex-end;
		gap: 0.5rem;
	}

	.textarea-wrapper {
		flex: 1;
		position: relative;
	}

	.compose-input {
		width: 100%;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 6px;
		padding: 0.6rem 0.75rem;
		font-size: 0.9rem;
		font-family: inherit;
		resize: none;
		max-height: 200px;
		line-height: 1.4;
	}

	.compose-input::placeholder {
		color: var(--text-muted);
	}

	.forum-input {
		min-height: 6rem;
	}

	.compose-input:focus {
		outline: none;
		border-color: var(--accent, #5865f2);
	}

	.compose-input.rate-limited {
		border-color: #e74c3c;
		opacity: 0.6;
	}

	.rate-limit-banner {
		position: absolute;
		bottom: calc(100% + 4px);
		left: 0;
		right: 0;
		background: #e74c3c;
		color: white;
		font-size: 0.78rem;
		padding: 0.3rem 0.6rem;
		border-radius: 4px;
		z-index: 5;
	}

	.attach-btn {
		background: none;
		border: none;
		cursor: pointer;
		color: var(--text);
		font-size: 1.2rem;
		padding: 0.4rem;
		border-radius: 4px;
		opacity: 0.7;
	}

	.attach-btn:disabled {
		opacity: 0.3;
		cursor: not-allowed;
	}

	.attach-btn:not(:disabled):hover {
		opacity: 1;
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.send-btn {
		padding: 0.6rem 1rem;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 6px;
		font-size: 0.875rem;
		cursor: pointer;
		white-space: nowrap;
	}

	.send-btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.send-btn:not(:disabled):hover {
		opacity: 0.9;
	}

	.file-previews {
		display: flex;
		gap: 0.5rem;
		flex-wrap: wrap;
		margin-bottom: 0.5rem;
	}

	.file-preview {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 6px;
		padding: 0.4rem 0.6rem;
		font-size: 0.8rem;
		max-width: 250px;
	}

	.file-preview.error {
		border-color: #e74c3c;
	}

	.preview-thumb {
		width: 40px;
		height: 40px;
		object-fit: cover;
		border-radius: 4px;
	}

	.preview-icon {
		font-size: 1.5rem;
	}

	.preview-info {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.preview-name {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		color: var(--text);
	}

	.preview-size {
		color: var(--text-muted);
		font-size: 0.7rem;
	}

	.preview-status {
		font-size: 0.75rem;
	}

	.error-text {
		color: #e74c3c;
	}

	.retry-btn {
		background: none;
		border: none;
		cursor: pointer;
		color: #e74c3c;
		font-size: 1rem;
		padding: 0.15rem 0.3rem;
		border-radius: 3px;
	}

	.retry-btn:hover {
		color: #ff6b5a;
		background: var(--bg-hover, rgba(255, 255, 255, 0.1));
	}

	.remove-btn {
		background: none;
		border: none;
		cursor: pointer;
		color: var(--text-muted);
		font-size: 0.8rem;
		padding: 0.15rem 0.3rem;
		border-radius: 3px;
	}

	.remove-btn:hover {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.1));
	}

	.emoji-btn-wrapper {
		position: relative;
	}

	.emoji-picker-anchor {
		position: absolute;
		bottom: calc(100% + 4px);
		left: 0;
		z-index: 10;
	}

	.gif-btn-wrapper {
		position: relative;
	}

	.gif-picker-anchor {
		position: absolute;
		bottom: calc(100% + 4px);
		left: 0;
		z-index: 10;
	}
</style>
