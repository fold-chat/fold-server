<script lang="ts">
	import {
		getChannelsByCategory,
		getChannels,
		getUnreadCount,
		reorderCategoriesLocal,
		reorderChannelsLocal,
		removeChannel as removeChannelStore,
		removeCategory as removeCategoryStore
	} from '$lib/stores/channels.svelte.js';
	import {
		reorderCategories,
		reorderChannels,
		updateCategory as updateCategoryApi,
		deleteChannel as deleteChannelApi,
		deleteCategory as deleteCategoryApi,
		createCategory as createCategoryApi
	} from '$lib/api/channels.js';
	import { updateChannel as updateChannelStore, updateCategory as updateCategoryStore } from '$lib/stores/channels.svelte.js';
	import type { Category, Channel } from '$lib/api/channels.js';
	import { getActiveChannelId } from '$lib/stores/messages.svelte.js';
	import { getConnectionState } from '$lib/stores/ws.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import { goto } from '$app/navigation';
import ConfirmDialog from './ConfirmDialog.svelte';
	import CreateChannelDialog from './CreateChannelDialog.svelte';

	const canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));
	const canManageRoles = $derived(hasServerPermission(PermissionName.MANAGE_ROLES));

	// --- Channel dialog state (create + edit) ---
	let channelDialogOpen = $state(false);
	let editingChannel = $state<Channel | null>(null);

	function handleCreateChannel() {
		editingChannel = null;
		channelDialogOpen = true;
	}

	function startEditChannel(e: Event, channel: Channel) {
		e.stopPropagation();
		e.preventDefault();
		editingChannel = channel;
		channelDialogOpen = true;
	}

	function onChannelSaved(ch: Channel) {
		channelDialogOpen = false;
		if (editingChannel) {
			updateChannelStore(ch);
		} else {
			goto(`/channels/${ch.id}`);
		}
		editingChannel = null;
	}

	async function handleCreateCategory() {
		try {
			const cat = await createCategoryApi({ name: 'new category' });
			editingCategoryId = cat.id;
			editingCategoryName = cat.name;
		} catch { /* ignore */ }
	}


	// --- Category rename state ---
	let editingCategoryId = $state<string | null>(null);
	let editingCategoryName = $state('');

	function startCategoryRename(e: Event, category: Category) {
		e.stopPropagation();
		e.preventDefault();
		editingCategoryId = category.id;
		editingCategoryName = category.name;
	}

	function commitCategoryRename(category: Category) {
		const trimmed = editingCategoryName.trim();
		editingCategoryId = null;
		if (!trimmed || trimmed === category.name) return;
		updateCategoryStore({ ...category, name: trimmed });
		updateCategoryApi(category.id, { name: trimmed }).catch(() => {
			updateCategoryStore(category);
		});
	}

	function onCategoryRenameKeydown(e: KeyboardEvent, category: Category) {
		if (e.key === 'Enter') {
			e.preventDefault();
			commitCategoryRename(category);
		} else if (e.key === 'Escape') {
			editingCategoryId = null;
		}
	}

	// --- Confirm dialog state ---
	let confirmOpen = $state(false);
	let confirmTitle = $state('');
	let confirmMessage = $state('');
	let confirmAction = $state<(() => void) | null>(null);

	function openConfirm(title: string, message: string, action: () => void) {
		confirmTitle = title;
		confirmMessage = message;
		confirmAction = action;
		confirmOpen = true;
	}

	function closeConfirm() {
		confirmOpen = false;
		confirmAction = null;
	}

	function doConfirm() {
		if (confirmAction) confirmAction();
		closeConfirm();
	}

	// --- Delete handlers ---
	function requestDeleteChannel(e: Event, channel: Channel) {
		e.stopPropagation();
		e.preventDefault();
		openConfirm(
			`Delete #${channel.name}?`,
			'This channel and all its messages will be permanently deleted.',
			() => {
				const wasActive = getActiveChannelId() === channel.id;
				removeChannelStore(channel.id);
				deleteChannelApi(channel.id).catch(() => {});
				if (wasActive) goto('/');
			}
		);
	}

	function requestDeleteCategory(e: Event, category: Category, channelCount: number) {
		e.stopPropagation();
		e.preventDefault();
		const chMsg = channelCount > 0
			? `This will also permanently delete ${channelCount} channel${channelCount === 1 ? '' : 's'} and all their messages.`
			: 'This category is empty.';
		openConfirm(
			`Delete ${category.name}?`,
			chMsg,
			() => {
				const activeId = getActiveChannelId();
				const catChannels = getChannels().filter(c => c.category_id === category.id);
				for (const ch of catChannels) removeChannelStore(ch.id);
				removeCategoryStore(category.id);
				deleteCategoryApi(category.id).catch(() => {});
				if (activeId && catChannels.some(c => c.id === activeId)) goto('/');
			}
		);
	}

	// --- Category collapse state ---
	const COLLAPSED_KEY = 'fray_collapsed_categories';
	let collapsedCategories = $state<Set<string>>(loadCollapsed());

	function loadCollapsed(): Set<string> {
		try {
			const raw = localStorage.getItem(COLLAPSED_KEY);
			return raw ? new Set(JSON.parse(raw)) : new Set();
		} catch {
			return new Set();
		}
	}

	function toggleCategory(id: string) {
		const next = new Set(collapsedCategories);
		if (next.has(id)) next.delete(id);
		else next.add(id);
		collapsedCategories = next;
		localStorage.setItem(COLLAPSED_KEY, JSON.stringify([...next]));
	}

	// Drag-and-drop state
	let dragType = $state<'category' | 'channel' | null>(null);
	let dragId = $state<string | null>(null);
	let dropMark = $state<{
		id: string;
		type: 'category' | 'channel';
		position: 'before' | 'after' | 'into';
	} | null>(null);

	function selectChannel(id: string) {
		goto(`/channels/${id}`);
	}

	// --- Drag handlers ---

	function onCategoryDragStart(e: DragEvent, category: Category) {
		dragType = 'category';
		dragId = category.id;
		e.dataTransfer!.effectAllowed = 'move';
	}

	function onChannelDragStart(e: DragEvent, channel: Channel) {
		dragType = 'channel';
		dragId = channel.id;
		e.dataTransfer!.effectAllowed = 'move';
	}

	function onDragEnd() {
		dragType = null;
		dragId = null;
		dropMark = null;
	}

	function onCategoryDragOver(e: DragEvent, category: Category) {
		if (!dragType || dragId === category.id) return;
		e.preventDefault();
		e.dataTransfer!.dropEffect = 'move';

		if (dragType === 'category') {
			const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
			const position = e.clientY - rect.top < rect.height / 2 ? 'before' : 'after';
			dropMark = { id: category.id, type: 'category', position };
		} else {
			dropMark = { id: category.id, type: 'category', position: 'into' };
		}
	}

	function onChannelDragOver(e: DragEvent, channel: Channel) {
		if (dragType !== 'channel' || dragId === channel.id) return;
		e.preventDefault();
		e.dataTransfer!.dropEffect = 'move';

		const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
		const position = e.clientY - rect.top < rect.height / 2 ? 'before' : 'after';
		dropMark = { id: channel.id, type: 'channel', position };
	}

	function onListDragLeave(e: DragEvent) {
		const related = e.relatedTarget as HTMLElement | null;
		if (related && (e.currentTarget as HTMLElement).contains(related)) return;
		dropMark = null;
	}

	function onItemDrop(e: DragEvent) {
		e.preventDefault();
		if (!dropMark || !dragId || !dragType) {
			onDragEnd();
			return;
		}
		if (dragType === 'category') doCategoryDrop();
		else doChannelDrop();
		onDragEnd();
	}

	function doCategoryDrop() {
		if (!dropMark || dropMark.type !== 'category') return;
		const groups = getChannelsByCategory();
		const cats = groups.filter((g) => g.category).map((g) => g.category!);

		const remaining = cats.filter((c) => c.id !== dragId);
		const targetIdx = remaining.findIndex((c) => c.id === dropMark!.id);
		if (targetIdx === -1) return;

		const insertIdx = dropMark.position === 'after' ? targetIdx + 1 : targetIdx;
		const dragged = cats.find((c) => c.id === dragId)!;
		remaining.splice(insertIdx, 0, dragged);

		const items = remaining.map((c, i) => ({ id: c.id, position: i }));
		reorderCategoriesLocal(items);
		reorderCategories(items).catch(() => {});
	}

	function doChannelDrop() {
		if (!dropMark || !dragId) return;
		const allChannels = getChannels();
		const dragged = allChannels.find((c) => c.id === dragId);
		if (!dragged) return;

		let targetCategoryId: string | null;
		let catChannels: Channel[];
		let insertIdx: number;

		if (dropMark.type === 'category' && dropMark.position === 'into') {
			targetCategoryId = dropMark.id;
			catChannels = allChannels
				.filter((c) => c.category_id === targetCategoryId && c.id !== dragId)
				.sort((a, b) => a.position - b.position);
			insertIdx = catChannels.length;
		} else if (dropMark.type === 'channel') {
			const target = allChannels.find((c) => c.id === dropMark!.id);
			if (!target) return;
			targetCategoryId = target.category_id;
			catChannels = allChannels
				.filter((c) => c.category_id === targetCategoryId && c.id !== dragId)
				.sort((a, b) => a.position - b.position);
			const tIdx = catChannels.findIndex((c) => c.id === dropMark!.id);
			insertIdx = dropMark.position === 'after' ? tIdx + 1 : tIdx;
		} else {
			return;
		}

		catChannels.splice(insertIdx, 0, dragged);
		const items: { id: string; position: number; category_id: string | null }[] = catChannels.map(
			(c, i) => ({ id: c.id, position: i, category_id: targetCategoryId })
		);

		// Reposition source category if moved across categories
		if (dragged.category_id !== targetCategoryId) {
			allChannels
				.filter((c) => c.category_id === dragged.category_id && c.id !== dragId)
				.sort((a, b) => a.position - b.position)
				.forEach((c, i) =>
					items.push({ id: c.id, position: i, category_id: dragged.category_id })
				);
		}

		reorderChannelsLocal(items);
		reorderChannels(items).catch(() => {});
	}
</script>

<aside class="sidebar">
	{#if getConnectionState() !== 'connected'}
		<div class="connection-banner" class:reconnecting={getConnectionState() === 'reconnecting'}>
			{getConnectionState() === 'reconnecting' ? 'Reconnecting...' : 'Disconnected'}
		</div>
	{/if}

	<div class="sidebar-header">
		<h2>Fray</h2>
	</div>

	<nav class="channel-list" ondragleave={onListDragLeave}>
		{#each getChannelsByCategory() as group}
			{#if group.category}
				<div
					class="category-header"
					class:drop-before={dropMark?.id === group.category.id && dropMark?.type === 'category' && dropMark?.position === 'before'}
					class:drop-after={dropMark?.id === group.category.id && dropMark?.type === 'category' && dropMark?.position === 'after'}
					class:drop-into={dropMark?.id === group.category.id && dropMark?.position === 'into'}
					class:dragging={dragType === 'category' && dragId === group.category.id}
					draggable={canManageChannels}
					ondragstart={(e: DragEvent) => onCategoryDragStart(e, group.category!)}
					ondragend={onDragEnd}
					ondragover={(e: DragEvent) => onCategoryDragOver(e, group.category!)}
					ondrop={onItemDrop}
					role="listitem"
				>
				{#if canManageChannels}
					<span class="drag-handle" aria-hidden="true">⠿</span>
				{/if}
					<button class="collapse-toggle" onclick={() => toggleCategory(group.category!.id)}>
						<span class="chevron" class:collapsed={collapsedCategories.has(group.category!.id)}>▾</span>
					</button>
					{#if editingCategoryId === group.category!.id}
						<!-- svelte-ignore a11y_autofocus -->
						<input
							class="category-rename-input"
							type="text"
							bind:value={editingCategoryName}
							autofocus
							onblur={() => commitCategoryRename(group.category!)}
							onkeydown={(e: KeyboardEvent) => onCategoryRenameKeydown(e, group.category!)}
							onclick={(e: MouseEvent) => e.stopPropagation()}
						/>
					{:else}
						<span class="category-name" onclick={() => toggleCategory(group.category!.id)} onkeydown={(e: KeyboardEvent) => { if (e.key === 'Enter' || e.key === ' ') toggleCategory(group.category!.id); }} role="button" tabindex="0">{group.category.name}</span>
					{/if}
					{#if canManageChannels && editingCategoryId !== group.category!.id}
						<span class="category-action rename-btn" role="button" tabindex="0" onclick={(e) => startCategoryRename(e, group.category!)} onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') startCategoryRename(e, group.category!); }} title="Rename">✎</span>
						<span class="category-action delete-btn" role="button" tabindex="0" onclick={(e) => requestDeleteCategory(e, group.category!, group.channels.length)} onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') requestDeleteCategory(e, group.category!, group.channels.length); }} title="Delete">🗑</span>
					{/if}
				</div>
			{/if}
			{#if !group.category || !collapsedCategories.has(group.category.id)}
			{#each group.channels as channel}
				{@const unread = getUnreadCount(channel.id)}
				<button
					class="channel-item"
					class:active={getActiveChannelId() === channel.id}
					class:unread={unread > 0}
					class:drop-before={dropMark?.id === channel.id && dropMark?.position === 'before'}
					class:drop-after={dropMark?.id === channel.id && dropMark?.position === 'after'}
					class:dragging={dragType === 'channel' && dragId === channel.id}
					draggable={canManageChannels}
					ondragstart={(e: DragEvent) => onChannelDragStart(e, channel)}
					ondragend={onDragEnd}
					ondragover={(e: DragEvent) => onChannelDragOver(e, channel)}
					ondrop={onItemDrop}
				onclick={() => selectChannel(channel.id)}
				>
				{#if canManageChannels}
					<span class="drag-handle" aria-hidden="true">⠿</span>
				{/if}
					<span class="channel-hash">{channel.type === 'THREAD_CHANNEL' ? '💬' : '#'}</span>
					<span class="channel-name">{channel.name}</span>
					{#if unread > 0}
						<span class="unread-badge">{unread > 99 ? '99+' : unread}</span>
					{/if}
				{#if canManageChannels}
						<span class="rename-btn" role="button" tabindex="0" onclick={(e) => startEditChannel(e, channel)} onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') startEditChannel(e, channel); }} title="Edit">✎</span>
						<span class="delete-btn" role="button" tabindex="0" onclick={(e) => requestDeleteChannel(e, channel)} onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') requestDeleteChannel(e, channel); }} title="Delete">🗑</span>
					{/if}
				</button>
			{/each}
			{/if}
		{/each}
	</nav>

	<div class="sidebar-footer">
		{#if canManageChannels}
			<button class="sidebar-action" onclick={handleCreateChannel}>+ Channel</button>
			<button class="sidebar-action" onclick={handleCreateCategory}>+ Category</button>
		{/if}
		{#if canManageRoles}
			<button class="sidebar-action" onclick={() => goto('/settings/roles')}>Roles</button>
			<button class="sidebar-action" onclick={() => goto('/settings/members')}>Members</button>
		{/if}
	</div>
</aside>

<CreateChannelDialog
	open={channelDialogOpen}
	channel={editingChannel}
	onsave={onChannelSaved}
	oncancel={() => { channelDialogOpen = false; editingChannel = null; }}
/>

<ConfirmDialog
	open={confirmOpen}
	title={confirmTitle}
	message={confirmMessage}
	onconfirm={doConfirm}
	oncancel={closeConfirm}
/>

<style>
	.sidebar {
		width: 240px;
		min-width: 240px;
		background: var(--bg-surface);
		border-right: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		height: 100vh;
	}

	.connection-banner {
		padding: 0.4rem 0.75rem;
		font-size: 0.75rem;
		text-align: center;
		background: var(--danger, #e74c3c);
		color: white;
	}

	.connection-banner.reconnecting {
		background: var(--warning, #f39c12);
	}

	.sidebar-header {
		padding: 1rem;
		border-bottom: 1px solid var(--border);
	}

	.sidebar-header h2 {
		font-size: 1.1rem;
		margin: 0;
	}

	.channel-list {
		flex: 1;
		overflow-y: auto;
		padding: 0.5rem 0;
	}

	.category-header {
		padding: 0.5rem 1rem 0.25rem;
		display: flex;
		align-items: center;
		gap: 0.25rem;
	}

	.category-name {
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
	}

	.channel-item {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		width: 100%;
		padding: 0.35rem 1rem;
		border: none;
		background: none;
		color: var(--text-muted);
		font-size: 0.875rem;
		cursor: pointer;
		text-align: left;
		border-radius: 4px;
		margin: 0 0.5rem;
		width: calc(100% - 1rem);
	}

	.channel-item:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	.channel-item.active {
		background: var(--bg-active, rgba(255, 255, 255, 0.1));
		color: var(--text);
	}

	.channel-item.unread {
		color: var(--text);
		font-weight: 600;
	}

	.channel-hash {
		opacity: 0.5;
		font-weight: 500;
	}

	.channel-name {
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.unread-badge {
		background: var(--accent, #5865f2);
		color: white;
		font-size: 0.65rem;
		font-weight: 700;
		padding: 0.1rem 0.4rem;
		border-radius: 8px;
		min-width: 1rem;
		text-align: center;
	}

	.sidebar-footer {
		padding: 0.5rem;
		border-top: 1px solid var(--border);
		display: flex;
		gap: 0.5rem;
	}

	.sidebar-action {
		flex: 1;
		padding: 0.4rem;
		font-size: 0.75rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 4px;
		cursor: pointer;
	}

	.sidebar-action:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	/* Collapse toggle */
	.collapse-toggle {
		background: none;
		border: none;
		padding: 0;
		cursor: pointer;
		display: flex;
		align-items: center;
		color: var(--text-muted);
	}

	.chevron {
		font-size: 0.6rem;
		transition: transform 0.15s;
		display: inline-block;
	}

	.chevron.collapsed {
		transform: rotate(-90deg);
	}

	/* Category rename */
	.category-rename-input {
		background: var(--bg-surface);
		color: var(--text);
		border: 1px solid var(--accent, #5865f2);
		border-radius: 3px;
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		padding: 0 0.25rem;
		outline: none;
		min-width: 0;
		flex: 1;
	}

	.category-action {
		background: none;
		border: none;
		padding: 0 0.1rem;
		cursor: pointer;
		color: var(--text-muted);
		font-size: 0.55rem;
		opacity: 0;
		transition: opacity 0.15s;
		flex-shrink: 0;
		margin-left: auto;
	}

	.category-header:hover .category-action {
		opacity: 0.5;
	}

	.category-action:hover {
		opacity: 1 !important;
	}

	/* Inline rename */
	.rename-input {
		flex: 1;
		background: var(--bg-surface);
		color: var(--text);
		border: 1px solid var(--accent, #5865f2);
		border-radius: 3px;
		font-size: 0.875rem;
		padding: 0 0.25rem;
		outline: none;
		min-width: 0;
	}

	.rename-btn {
		background: none;
		border: none;
		padding: 0 0.15rem;
		cursor: pointer;
		color: var(--text-muted);
		font-size: 0.7rem;
		opacity: 0;
		transition: opacity 0.15s;
		flex-shrink: 0;
	}

	.channel-item:hover .rename-btn {
		opacity: 0.5;
	}

	.rename-btn:hover {
		opacity: 1 !important;
	}

	.delete-btn {
		background: none;
		border: none;
		padding: 0 0.15rem;
		cursor: pointer;
		color: var(--text-muted);
		font-size: 0.6rem;
		opacity: 0;
		transition: opacity 0.15s;
		flex-shrink: 0;
	}

	.channel-item:hover .delete-btn {
		opacity: 0.5;
	}

	.delete-btn:hover {
		opacity: 1 !important;
		color: var(--danger, #e74c3c);
	}

	/* Drag-and-drop */
	.drag-handle {
		cursor: grab;
		opacity: 0;
		font-size: 0.6rem;
		color: var(--text-muted);
		user-select: none;
		flex-shrink: 0;
		transition: opacity 0.15s;
		line-height: 1;
	}

	.category-header:hover .drag-handle,
	.channel-item:hover .drag-handle {
		opacity: 0.5;
	}

	.drag-handle:hover {
		opacity: 1 !important;
	}

	.dragging {
		opacity: 0.4;
	}

	.drop-before {
		box-shadow: 0 -2px 0 0 var(--accent, #5865f2);
	}

	.drop-after {
		box-shadow: 0 2px 0 0 var(--accent, #5865f2);
	}

	.drop-into {
		background: rgba(88, 101, 242, 0.15) !important;
	}
</style>
