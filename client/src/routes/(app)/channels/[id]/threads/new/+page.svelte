<script lang="ts">
	import { page } from '$app/state';
	import { goto } from '$app/navigation';
	import { createThread } from '$lib/api/threads.js';
	import { addChannelThread } from '$lib/stores/threads.svelte.js';
	import { getChannelById, getCategories } from '$lib/stores/channels.svelte.js';
	import { hasChannelPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import MessageCompose from '$lib/components/MessageCompose.svelte';

	let channelId = $derived(page.params.id!);
	let channel = $derived(getChannelById(channelId));
	let categoryName = $derived.by(() => {
		if (!channel?.category_id) return null;
		return getCategories().find(c => c.id === channel!.category_id)?.name ?? null;
	});
	let canUploadFiles = $derived(hasChannelPermission(channelId, PermissionName.UPLOAD_FILES));

	let title = $state('');
	let submitting = $state(false);
	let titleError = $state('');

	async function handleSubmit(content: string, attachmentIds?: string[]) {
		titleError = '';
		if (!title.trim()) {
			titleError = 'Title is required';
			return;
		}
		if (!content.trim()) return;
		submitting = true;
		try {
			const thread = await createThread(channelId, { title: title.trim(), content, attachment_ids: attachmentIds });
			addChannelThread(thread);
			goto(`/channels/${channelId}/threads/${thread.id}`);
		} catch {
			// handle error
		} finally {
			submitting = false;
		}
	}

	function goBack() {
		goto(`/channels/${channelId}`);
	}
</script>

<div class="new-thread-page">
	<div class="new-thread-header">
		<nav class="breadcrumb">
			{#if categoryName}
				<span class="bc-muted">{categoryName}</span>
				<span class="bc-sep">›</span>
			{/if}
			{#if channel}
				<button class="bc-link" onclick={goBack}># {channel.name}</button>
			{/if}
		</nav>
	</div>

	<div class="new-thread-body">
		<h1 class="page-title">New Thread</h1>
		<input
			class="title-input"
			class:error={!!titleError}
			type="text"
			placeholder="Thread title"
			bind:value={title}
			disabled={submitting}
		/>
		{#if titleError}
			<span class="title-error">{titleError}</span>
		{/if}
		<MessageCompose onSend={handleSubmit} disabled={submitting} {canUploadFiles} forumMode />
	</div>
</div>

<style>
	.new-thread-page {
		flex: 1;
		display: flex;
		flex-direction: column;
		height: 100vh;
		min-width: 0;
	}

	.new-thread-header {
		display: flex;
		align-items: center;
		padding: 0.5rem 1.5rem;
		border-bottom: 1px solid var(--border);
		min-height: 44px;
		flex-shrink: 0;
	}

	.breadcrumb {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		min-width: 0;
		overflow: hidden;
	}

	.bc-muted {
		font-size: 0.8rem;
		color: var(--text-muted);
		white-space: nowrap;
	}

	.bc-sep {
		font-size: 0.8rem;
		color: var(--text-muted);
		opacity: 0.5;
	}

	.bc-link {
		background: none;
		border: none;
		padding: 0;
		font-size: 0.8rem;
		color: var(--text-muted);
		cursor: pointer;
		white-space: nowrap;
	}

	.bc-link:hover {
		color: var(--accent, #5865f2);
	}

	.new-thread-body {
		flex: 1;
		display: flex;
		flex-direction: column;
		padding: 1.5rem;
		gap: 0.75rem;
		max-width: 800px;
		width: 100%;
		margin: 0 auto;
	}

	.page-title {
		font-size: 1.25rem;
		font-weight: 700;
		margin: 0;
	}

	.title-input {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 8px;
		padding: 0.75rem 1rem;
		font-size: 1.1rem;
		font-family: inherit;
		font-weight: 600;
	}

	.title-input:focus {
		outline: none;
		border-color: var(--accent, #5865f2);
	}

	.title-input.error {
		border-color: var(--danger, #e74c3c);
	}

	.title-error {
		font-size: 0.78rem;
		color: var(--danger, #e74c3c);
		margin-top: -0.25rem;
	}
</style>
