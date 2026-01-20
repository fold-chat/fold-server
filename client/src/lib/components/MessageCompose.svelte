<script lang="ts">
	import { uploadFile } from '$lib/api/messages.js';
	import EmojiPicker from './EmojiPicker.svelte';

	interface PendingFile {
		file: File;
		id?: string;
		url?: string;
		uploading: boolean;
		error?: string;
		preview?: string;
	}

	let { onSend, onTyping, disabled = false }: { onSend: (content: string, attachmentIds?: string[]) => void; onTyping?: () => void; disabled?: boolean } = $props();

	let content = $state('');
	let textarea = $state<HTMLTextAreaElement | null>(null);
	let fileInput = $state<HTMLInputElement | null>(null);
	let pendingFiles = $state<PendingFile[]>([]);
	let dragging = $state(false);
	let showEmojiPicker = $state(false);
	let canSend = $derived(content.trim().length > 0 || pendingFiles.length > 0);

	function handleKeydown(e: KeyboardEvent) {
		if (e.key === 'Enter' && !e.shiftKey) {
			e.preventDefault();
			submit();
		}
	}

	function handleInput() {
		onTyping?.();
		autoResize();
	}

	async function submit() {
		const trimmed = content.trim();
		const uploading = pendingFiles.some(f => f.uploading);
		if (!trimmed && pendingFiles.length === 0) return;
		if (uploading) return;

		const ids = pendingFiles.filter(f => f.id).map(f => f.id!);
	onSend(trimmed, ids.length > 0 ? ids : undefined);
		content = '';
		pendingFiles = [];
		if (textarea) textarea.style.height = 'auto';
	}

	function autoResize() {
		if (!textarea) return;
		textarea.style.height = 'auto';
		textarea.style.height = Math.min(textarea.scrollHeight, 200) + 'px';
	}

	function addFiles(files: FileList | File[]) {
		for (const file of files) {
			const pf: PendingFile = { file, uploading: true };
			if (file.type.startsWith('image/')) {
				pf.preview = URL.createObjectURL(file);
			}
			pendingFiles = [...pendingFiles, pf];
			uploadPending(pf);
		}
	}

	async function uploadPending(pf: PendingFile) {
		const file = pf.file;
		try {
			const result = await uploadFile(file);
			pendingFiles = pendingFiles.map(f =>
				f.file === file && f.uploading
					? { ...f, id: result.id, url: result.url, uploading: false }
					: f
			);
		} catch {
			pendingFiles = pendingFiles.map(f =>
				f.file === file && f.uploading
					? { ...f, error: 'Upload failed', uploading: false }
					: f
			);
		}
	}

	function removeFile(idx: number) {
		const pf = pendingFiles[idx];
		if (pf.preview) URL.revokeObjectURL(pf.preview);
		pendingFiles = pendingFiles.filter((_, i) => i !== idx);
	}

	function handleDragOver(e: DragEvent) {
		e.preventDefault();
		dragging = true;
	}

	function handleDragLeave() {
		dragging = false;
	}

	function handleDrop(e: DragEvent) {
		e.preventDefault();
		dragging = false;
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
						<span class="preview-status error-text">{pf.error}</span>
					{:else}
						<span class="preview-status">✓</span>
					{/if}
					<button class="remove-btn" onclick={() => removeFile(i)} title="Remove">✕</button>
				</div>
			{/each}
		</div>
	{/if}
	<div class="compose-row">
		<button class="attach-btn" onclick={() => fileInput?.click()} title="Attach file">📎</button>
		<input type="file" bind:this={fileInput} onchange={handleFileSelect} multiple hidden />
		<div class="emoji-btn-wrapper">
			<button class="attach-btn" onclick={() => showEmojiPicker = !showEmojiPicker} title="Emoji">😀</button>
			{#if showEmojiPicker}
				<div class="emoji-picker-anchor">
					<EmojiPicker onSelect={insertEmoji} onClose={() => showEmojiPicker = false} />
				</div>
			{/if}
		</div>
		<textarea
			class="compose-input"
			bind:this={textarea}
			bind:value={content}
			onkeydown={handleKeydown}
			oninput={handleInput}
			placeholder={disabled ? 'You do not have permission to send messages' : 'Send a message...'}
			rows="1"
			{disabled}
		></textarea>
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

	.compose-input {
		flex: 1;
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

	.compose-input:focus {
		outline: none;
		border-color: var(--accent, #5865f2);
	}

	.attach-btn {
		background: none;
		border: none;
		cursor: pointer;
		font-size: 1.2rem;
		padding: 0.4rem;
		border-radius: 4px;
		opacity: 0.7;
	}

	.attach-btn:hover {
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
</style>
