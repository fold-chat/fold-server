<script lang="ts">
import {
		getChannelsByCategory,
		getChannels,
		getUnreadCount,
		getMentionCount,
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
import { hasServerPermission, hasChannelPermission, getUser, getServerSettings, getServerName } from '$lib/stores/auth.svelte.js';
	import { getMembers } from '$lib/stores/members.svelte.js';
	import { DEFAULT_ICONS } from '$lib/icons.js';
	import { PermissionName } from '$lib/permissions.js';
	import { goto } from '$app/navigation';
import ConfirmDialog from './ConfirmDialog.svelte';
import { openMemberProfile } from '$lib/stores/membersPanel.svelte.js';
	import CreateChannelDialog from './CreateChannelDialog.svelte';
import { isSidebarCollapsed, isSidebarExpanded, isNarrowScreen, closeSidebar, toggleSidebar } from '$lib/stores/sidebar.svelte.js';
import { getDmConversations, getDmUnreadCount, getTotalDmUnreadCount, ensureDmLoaded } from '$lib/stores/dm.svelte.js';
import { getVoiceStatesForChannel, getCurrentVoiceChannelId, getJoiningChannelId, isLocalAudioMuted, isLocalDeafened, isServerMuted, isServerDeafened, leaveCurrentVoice, toggleMute, toggleDeafen, isSpeaking, isCameraActive, isScreenShareActive, toggleCamera, toggleScreenShare, isPttEnabled, isPttActive, isE2eeActive, isE2eeCapability, getLivekitConnectionState, getLastJoinError, optimisticSetServerMute, optimisticSetServerDeaf } from '$lib/stores/voice.svelte.js';
	import { getChannelById } from '$lib/stores/channels.svelte.js';
	import { serverMute, serverUnmute, serverDeafen, serverUndeafen, disconnectUser, moveUser } from '$lib/api/voice.js';
	import type { VoiceState } from '$lib/api/voice.js';

	const canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));

	// Lazy-load DM data for sidebar DM section
	ensureDmLoaded();

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
	const COLLAPSED_KEY = 'fold_collapsed_categories';
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

	function selectChannel(channel: Channel) {
		goto(`/channels/${channel.id}`);
		if (isNarrowScreen()) closeSidebar();
	}

	const voiceChannelName = $derived.by(() => {
		const id = getCurrentVoiceChannelId() ?? getJoiningChannelId();
		if (!id) return null;
		const ch = getChannelById(id);
		return ch?.name ?? null;
	});

	const canVideoInVoice = $derived.by(() => {
		const id = getCurrentVoiceChannelId();
		if (!id) return false;
		return hasChannelPermission(id, PermissionName.VIDEO);
	});

	const canMuteMembersInVoice = $derived.by(() => {
		const id = getCurrentVoiceChannelId();
		if (!id) return false;
		return hasChannelPermission(id, PermissionName.MUTE_MEMBERS);
	});

	const canDeafenMembersInVoice = $derived.by(() => {
		const id = getCurrentVoiceChannelId();
		if (!id) return false;
		return hasChannelPermission(id, PermissionName.DEAFEN_MEMBERS);
	});

	// --- Voice user context menu ---
	let voiceCtxMenu = $state<{ x: number; y: number; vu: VoiceState; channelId: string } | null>(null);

	function openVoiceCtx(e: MouseEvent, vu: VoiceState, channelId: string) {
		e.preventDefault();
		const me = getUser();
		if (!me || me.id === vu.user_id) return; // no self-moderation
		const canMute = hasChannelPermission(channelId, PermissionName.MUTE_MEMBERS);
		const canDeafen = hasChannelPermission(channelId, PermissionName.DEAFEN_MEMBERS);
		const canMove = hasChannelPermission(channelId, PermissionName.MOVE_MEMBERS);
		if (!canMute && !canDeafen && !canMove) return;
		voiceCtxMenu = { x: e.clientX, y: e.clientY, vu, channelId };
	}

	function closeVoiceCtx() { voiceCtxMenu = null; }

	async function doServerMute(vu: VoiceState, channelId: string) {
		const newState = !vu.server_mute;
		optimisticSetServerMute(channelId, vu.user_id, newState);
		closeVoiceCtx();
		try {
			if (vu.server_mute) await serverUnmute(channelId, vu.user_id);
			else await serverMute(channelId, vu.user_id);
		} catch {
			optimisticSetServerMute(channelId, vu.user_id, vu.server_mute);
		}
	}

	async function doServerDeafen(vu: VoiceState, channelId: string) {
		const newState = !vu.server_deaf;
		optimisticSetServerDeaf(channelId, vu.user_id, newState);
		closeVoiceCtx();
		try {
			if (vu.server_deaf) await serverUndeafen(channelId, vu.user_id);
			else await serverDeafen(channelId, vu.user_id);
		} catch {
			optimisticSetServerDeaf(channelId, vu.user_id, vu.server_deaf);
		}
	}

	async function doDisconnect(vu: VoiceState, channelId: string) {
		closeVoiceCtx();
		try { await disconnectUser(channelId, vu.user_id); } catch { /* ignore */ }
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

<aside class="sidebar" class:collapsed={isSidebarCollapsed()} class:expanded={isSidebarExpanded()} class:narrow={isNarrowScreen()}>
{#if isSidebarCollapsed()}
	<!-- Icon rail (narrow + collapsed) -->
	<div class="rail">
		<button class="rail-btn sidebar-toggle" onclick={toggleSidebar} title="Toggle sidebar">
			<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
				<line x1="3" y1="6" x2="21" y2="6" />
				<line x1="3" y1="12" x2="21" y2="12" />
				<line x1="3" y1="18" x2="21" y2="18" />
			</svg>
		</button>

		<a class="rail-server-icon" href="/" title={getServerName()}>
			{#if getServerSettings().server_icon}
				<img class="server-icon" src={getServerSettings().server_icon} alt="" />
			{:else}
				<span class="server-icon-fallback">{getServerName().charAt(0).toUpperCase()}</span>
			{/if}
		</a>

		<div class="rail-divider"></div>

		<nav class="rail-channels">
			{#each getChannelsByCategory().flatMap(g => g.channels) as channel}
				{@const unread = getUnreadCount(channel.id)}
				{@const mentions = getMentionCount(channel.id)}
				<button
					class="rail-channel"
					class:active={getActiveChannelId() === channel.id}
					class:unread={unread > 0}
					class:voice={channel.type === 'VOICE'}
					class:voice-connected={channel.type === 'VOICE' && getCurrentVoiceChannelId() === channel.id}
					title={channel.name}
					onclick={() => selectChannel(channel)}
				>
					{#if channel.icon_url}
						<img class="channel-icon-img" src={channel.icon_url} alt="" />
					{:else}
						<span class="channel-icon material-symbols-outlined">{channel.icon ?? DEFAULT_ICONS[channel.type] ?? 'tag'}</span>
					{/if}
					{#if mentions > 0}
						<span class="rail-badge mention">{mentions > 99 ? '99+' : mentions}</span>
					{:else if unread > 0}
						<span class="rail-badge"></span>
					{/if}
				</button>
			{/each}
		</nav>

	{#if getCurrentVoiceChannelId() || getJoiningChannelId()}
			<div class="rail-voice">
				<button class="voice-control-btn" class:active={isLocalAudioMuted() || isServerMuted()} title={isLocalAudioMuted() ? 'Unmute' : 'Mute'} onclick={() => toggleMute(canMuteMembersInVoice)} disabled={isServerMuted() && !canMuteMembersInVoice}>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
						{#if isLocalAudioMuted() || isServerMuted()}
							<line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" />
						{:else}
							<path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z" /><path d="M19 10v2a7 7 0 01-14 0v-2" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" />
						{/if}
					</svg>
				</button>
				<button class="voice-control-btn" class:active={isLocalDeafened() || isServerDeafened()} title={isLocalDeafened() ? 'Undeafen' : 'Deafen'} onclick={() => toggleDeafen(canDeafenMembersInVoice)} disabled={isServerDeafened() && !canDeafenMembersInVoice}>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
						{#if isLocalDeafened() || isServerDeafened()}
							<line x1="1" y1="1" x2="23" y2="23" /><path d="M3 12v6a9 9 0 009 3M21 12v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" />
						{:else}
							<path d="M3 18v-6a9 9 0 0118 0v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" />
						{/if}
					</svg>
				</button>
				<button class="voice-control-btn disconnect-btn" title="Disconnect" onclick={() => leaveCurrentVoice()}>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
						<path d="M10.68 13.31a16 16 0 003.41 2.6l1.27-1.27a2 2 0 012.11-.45 12.84 12.84 0 002.81.7 2 2 0 011.72 2v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6 19.79 19.79 0 01-3.07-8.67A2 2 0 014.11 2h3a2 2 0 012 1.72 12.84 12.84 0 00.7 2.81 2 2 0 01-.45 2.11L8.09 9.91" />
						<line x1="23" y1="1" x2="1" y2="23" />
					</svg>
				</button>
			</div>
		{/if}

		<div class="rail-footer">
			<a class="rail-btn" href="/settings/appearance" title="Appearance">
				<span class="material-symbols-outlined" style="font-size: 18px">palette</span>
			</a>
		</div>
	</div>
{:else}
	<!-- Full sidebar -->
	{#if getConnectionState() !== 'connected'}
		<div class="connection-banner" class:reconnecting={getConnectionState() === 'reconnecting'}>
			{getConnectionState() === 'reconnecting' ? 'Reconnecting...' : 'Disconnected'}
		</div>
	{/if}

	{#if isNarrowScreen()}
		<div class="sidebar-toggle-row">
			<button class="sidebar-toggle-btn" onclick={toggleSidebar} title="Close sidebar">
				<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="18" height="18">
					<line x1="3" y1="6" x2="21" y2="6" />
					<line x1="3" y1="12" x2="21" y2="12" />
					<line x1="3" y1="18" x2="21" y2="18" />
				</svg>
			</button>
		</div>
	{/if}

	<div class="server-header">
		<a class="server-header-link" href="/">
			{#if getServerSettings().server_icon}
				<img class="server-icon" src={getServerSettings().server_icon} alt="" />
			{:else}
				<span class="server-icon-fallback">{getServerName().charAt(0).toUpperCase()}</span>
			{/if}
			<div class="server-info">
				<span class="server-name">{getServerName()}</span>
				<span class="server-member-count">{getMembers().length} member{getMembers().length !== 1 ? 's' : ''}</span>
			</div>
		</a>
	</div>

	<nav class="channel-list" ondragleave={onListDragLeave}>
		{#each getChannelsByCategory() as group}
			{#if group.category}
				<!-- svelte-ignore a11y_no_static_element_interactions, a11y_click_events_have_key_events, a11y_no_noninteractive_element_interactions -->
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
					onclick={() => toggleCategory(group.category!.id)}
					role="listitem"
				>
				{#if canManageChannels}
					<span class="drag-handle" aria-hidden="true">⠿</span>
				{/if}
					<span class="chevron" class:collapsed={collapsedCategories.has(group.category!.id)}>▾</span>
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
						<span class="category-name">{group.category.name}</span>
					{/if}
					{#if canManageChannels && editingCategoryId !== group.category!.id}
						<span class="category-action rename-btn" role="button" tabindex="0" onclick={(e) => startCategoryRename(e, group.category!)} onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') startCategoryRename(e, group.category!); }} title="Rename">✎</span>
						<span class="category-action delete-btn" role="button" tabindex="0" onclick={(e) => requestDeleteCategory(e, group.category!, group.channels.length)} onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') requestDeleteCategory(e, group.category!, group.channels.length); }} title="Delete">🗑</span>
					{/if}
				</div>
			{/if}
			{#each group.channels as channel}
			{#if !group.category || !collapsedCategories.has(group.category.id) || getActiveChannelId() === channel.id}
			{@const unread = getUnreadCount(channel.id)}
			{@const mentions = getMentionCount(channel.id)}
			{@const isVoice = channel.type === 'VOICE'}
			{@const isArchived = !!channel.archived_at}
			{@const voiceUsers = isVoice ? getVoiceStatesForChannel(channel.id) : []}
				<div class="channel-wrapper">
				<button
					class="channel-item"
					class:active={getActiveChannelId() === channel.id}
					class:unread={unread > 0}
					class:archived={isArchived}
				class:voice={isVoice}
					class:voice-connected={isVoice && getCurrentVoiceChannelId() === channel.id}
					class:drop-before={dropMark?.id === channel.id && dropMark?.position === 'before'}
					class:drop-after={dropMark?.id === channel.id && dropMark?.position === 'after'}
					class:dragging={dragType === 'channel' && dragId === channel.id}
					draggable={canManageChannels}
					ondragstart={(e: DragEvent) => onChannelDragStart(e, channel)}
					ondragend={onDragEnd}
					ondragover={(e: DragEvent) => onChannelDragOver(e, channel)}
					ondrop={onItemDrop}
				onclick={() => selectChannel(channel)}
				>
				{#if canManageChannels}
					<span class="drag-handle" aria-hidden="true">⠿</span>
				{/if}
					{#if channel.icon_url}
						<img class="channel-icon-img" src={channel.icon_url} alt="" />
					{:else}
						<span class="channel-icon material-symbols-outlined">{channel.icon ?? DEFAULT_ICONS[channel.type] ?? 'tag'}</span>
					{/if}
					<span class="channel-name">{channel.name}</span>
					{#if isArchived}
						<span class="archived-badge">archived</span>
					{/if}
					{#if !isVoice}
						{#if mentions > 0}
							<span class="mention-badge">{mentions > 99 ? '99+' : mentions}</span>
						{:else if unread > 0}
							<span class="unread-badge">{unread > 99 ? '99+' : unread}</span>
						{/if}
					{/if}
				{#if canManageChannels}
						<span class="rename-btn" role="button" tabindex="0" onclick={(e) => startEditChannel(e, channel)} onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') startEditChannel(e, channel); }} title="Edit">✎</span>
						<span class="delete-btn" role="button" tabindex="0" onclick={(e) => requestDeleteChannel(e, channel)} onkeydown={(e) => { if (e.key === 'Enter' || e.key === ' ') requestDeleteChannel(e, channel); }} title="Delete">🗑</span>
					{/if}
				</button>
				{#if isVoice && voiceUsers.length > 0}
					<div class="voice-users">
						{#each voiceUsers.slice(0, 8) as vu}
					<!-- svelte-ignore a11y_no_static_element_interactions -->
					<div class="voice-user" class:muted={vu.self_mute || vu.server_mute} class:deafened={vu.self_deaf || vu.server_deaf} class:speaking={isSpeaking(vu.user_id)} oncontextmenu={(e) => openVoiceCtx(e, vu, channel.id)}>
								{#if vu.avatar_url}
									<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
									<img class="voice-avatar clickable" src={vu.avatar_url} alt="" onclick={(e) => { e.stopPropagation(); openMemberProfile(vu.user_id); }} />
								{:else}
									<!-- svelte-ignore a11y_no_static_element_interactions -->
									<span class="voice-avatar-placeholder clickable" onclick={(e) => { e.stopPropagation(); openMemberProfile(vu.user_id); }}>{(vu.display_name || vu.username).charAt(0).toUpperCase()}</span>
								{/if}
								<span class="voice-username">{vu.display_name || vu.username}</span>
								{#if vu.server_mute}<span class="voice-indicator server" title="Server muted"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" /></svg></span>
								{:else if vu.self_mute}<span class="voice-indicator" title="Muted"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" /><path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" /><line x1="12" y1="19" x2="12" y2="23" /><line x1="8" y1="23" x2="16" y2="23" /></svg></span>{/if}
								{#if vu.server_deaf}<span class="voice-indicator server" title="Server deafened"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M3 12v6a9 9 0 009 3M21 12v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" /></svg></span>
								{:else if vu.self_deaf}<span class="voice-indicator" title="Deafened"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="10" height="10"><line x1="1" y1="1" x2="23" y2="23" /><path d="M3 12v6a9 9 0 009 3M21 12v6" /><path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" /></svg></span>{/if}
							</div>
						{/each}
						{#if voiceUsers.length > 8}
							<div class="voice-user overflow">+{voiceUsers.length - 8}</div>
						{/if}
					</div>
				{/if}
				</div>
			{/if}
			{/each}
		{/each}
	</nav>

	<!-- Direct Messages section -->
	{@const recentDms = getDmConversations().slice(0, 5)}
	{#if recentDms.length > 0}
		<div class="dm-section">
			<a class="dm-section-header" href="/dm">
				<span class="category-name">Direct Messages</span>
				{#if getTotalDmUnreadCount() > 0}
					<span class="dm-total-badge">{getTotalDmUnreadCount() > 99 ? '99+' : getTotalDmUnreadCount()}</span>
				{/if}
			</a>
			{#each recentDms as dm}
				{@const me = getUser()}
				{@const other = dm.participants.find(p => p.id !== me?.id) ?? dm.participants[0]}
				{@const dmUnread = getDmUnreadCount(dm.channel_id)}
				<a
					class="dm-item"
					class:unread={dmUnread > 0}
					href="/dm/{dm.channel_id}"
					onclick={() => { if (isNarrowScreen()) closeSidebar(); }}
				>
					{#if other?.avatar_url}
						<img class="dm-avatar" src={other.avatar_url} alt="" />
					{:else}
						<span class="dm-avatar-placeholder">{(other?.display_name || other?.username || '?').charAt(0).toUpperCase()}</span>
					{/if}
					<span class="dm-name">{other?.display_name || other?.username || 'Unknown'}</span>
					{#if dmUnread > 0}
						<span class="unread-badge">{dmUnread > 99 ? '99+' : dmUnread}</span>
					{/if}
				</a>
			{/each}
		</div>
	{/if}

	{#if getLastJoinError()}
		<div class="voice-error-banner">
			<span>⚠️ {getLastJoinError()}</span>
		</div>
	{/if}

	{#if getCurrentVoiceChannelId() || getJoiningChannelId()}
		<div class="voice-bar">
			<div class="voice-bar-info">
				{#if getJoiningChannelId() && !getCurrentVoiceChannelId()}
					<span class="voice-bar-label" style="color: #f39c12">Joining…</span>
				{:else if getLivekitConnectionState() === 'reconnecting'}
					<span class="voice-bar-label" style="color: #f39c12">Reconnecting…</span>
				{:else}
				<span class="voice-bar-label">Voice Connected</span>
				{/if}
				<span class="voice-bar-channel">🔊 {voiceChannelName}
					{#if isE2eeCapability() && !isE2eeActive()}
						<span class="e2ee-warn" title="E2EE unavailable — audio is not encrypted (browser may not support it)">⚠ No E2EE</span>
					{/if}
				</span>
			</div>
			<div class="voice-bar-controls">
				<button
					class="voice-control-btn"
					class:active={isLocalAudioMuted() || isServerMuted()}
					class:server-enforced={isServerMuted() && !canMuteMembersInVoice}
					title={isServerMuted() ? (canMuteMembersInVoice ? 'Remove server mute' : 'Server muted') : isLocalAudioMuted() ? 'Unmute' : 'Mute'}
					onclick={() => toggleMute(canMuteMembersInVoice)}
					disabled={isServerMuted() && !canMuteMembersInVoice}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
						{#if isLocalAudioMuted() || isServerMuted()}
							<line x1="1" y1="1" x2="23" y2="23" />
							<path d="M9 9v3a3 3 0 005.12 2.12M15 9.34V4a3 3 0 00-5.94-.6" />
							<path d="M17 16.95A7 7 0 015 12v-2m14 0v2c0 .64-.09 1.26-.25 1.85" />
							<line x1="12" y1="19" x2="12" y2="23" />
							<line x1="8" y1="23" x2="16" y2="23" />
						{:else}
							<path d="M12 1a3 3 0 00-3 3v8a3 3 0 006 0V4a3 3 0 00-3-3z" />
							<path d="M19 10v2a7 7 0 01-14 0v-2" />
							<line x1="12" y1="19" x2="12" y2="23" />
							<line x1="8" y1="23" x2="16" y2="23" />
						{/if}
					</svg>
				</button>
				<button
					class="voice-control-btn"
					class:active={isLocalDeafened() || isServerDeafened()}
					class:server-enforced={isServerDeafened() && !canDeafenMembersInVoice}
					title={isServerDeafened() ? (canDeafenMembersInVoice ? 'Remove server deafen' : 'Server deafened') : isLocalDeafened() ? 'Undeafen' : 'Deafen'}
					onclick={() => toggleDeafen(canDeafenMembersInVoice)}
					disabled={isServerDeafened() && !canDeafenMembersInVoice}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
						{#if isLocalDeafened() || isServerDeafened()}
							<line x1="1" y1="1" x2="23" y2="23" />
							<path d="M3 12v6a9 9 0 009 3M21 12v6" />
							<path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" />
						{:else}
							<path d="M3 18v-6a9 9 0 0118 0v6" />
							<path d="M3 14h2a2 2 0 012 2v2a2 2 0 01-2 2H3v-6zM21 14h-2a2 2 0 00-2 2v2a2 2 0 002 2h2v-6z" />
						{/if}
					</svg>
				</button>
				{#if canVideoInVoice}
					<button
						class="voice-control-btn"
						class:active={isCameraActive()}
						title={isCameraActive() ? 'Turn off camera' : 'Turn on camera'}
						onclick={toggleCamera}
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
							{#if isCameraActive()}
								<path d="M23 7l-7 5 7 5V7z" />
								<rect x="1" y="5" width="15" height="14" rx="2" ry="2" />
							{:else}
								<line x1="1" y1="1" x2="23" y2="23" />
								<path d="M21 21H3a2 2 0 01-2-2V8a2 2 0 012-2h3m3-3h6l2 3h4a2 2 0 012 2v9.34" />
							{/if}
						</svg>
					</button>
					<button
						class="voice-control-btn"
						class:active={isScreenShareActive()}
						title={isScreenShareActive() ? 'Stop sharing' : 'Share screen'}
						onclick={toggleScreenShare}
					>
						<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
							<rect x="2" y="3" width="20" height="14" rx="2" ry="2" />
							<line x1="8" y1="21" x2="16" y2="21" />
							<line x1="12" y1="17" x2="12" y2="21" />
						</svg>
					</button>
				{/if}
				{#if isPttEnabled()}
					<span class="ptt-indicator" class:active={isPttActive()} title="Push to talk">PTT</span>
				{/if}
				<button
					class="voice-control-btn disconnect-btn"
					title="Disconnect"
					onclick={() => leaveCurrentVoice()}
				>
					<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" width="16" height="16">
						<path d="M10.68 13.31a16 16 0 003.41 2.6l1.27-1.27a2 2 0 012.11-.45 12.84 12.84 0 002.81.7 2 2 0 011.72 2v3a2 2 0 01-2.18 2 19.79 19.79 0 01-8.63-3.07 19.5 19.5 0 01-6-6 19.79 19.79 0 01-3.07-8.67A2 2 0 014.11 2h3a2 2 0 012 1.72 12.84 12.84 0 00.7 2.81 2 2 0 01-.45 2.11L8.09 9.91" />
						<line x1="23" y1="1" x2="1" y2="23" />
					</svg>
				</button>
			</div>
		</div>
	{/if}

	<div class="sidebar-footer">
		{#if canManageChannels}
			<button class="sidebar-create-btn" onclick={handleCreateChannel}>
				<span class="material-symbols-outlined" style="font-size: 16px">add</span>
				Channel
			</button>
			<button class="sidebar-create-btn" onclick={handleCreateCategory}>
				<span class="material-symbols-outlined" style="font-size: 16px">create_new_folder</span>
				Category
			</button>
		{/if}
		<a class="sidebar-theme-btn" href="/settings/appearance" title="Appearance">
			<span class="material-symbols-outlined" style="font-size: 16px">palette</span>
		</a>
	</div>
{/if}
</aside>

{#if voiceCtxMenu}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="voice-ctx-overlay" onclick={closeVoiceCtx} onkeydown={(e) => { if (e.key === 'Escape') closeVoiceCtx(); }}>
		<div class="voice-ctx-menu" style="left:{voiceCtxMenu.x}px;top:{voiceCtxMenu.y}px">
			<div class="voice-ctx-header">{voiceCtxMenu.vu.display_name || voiceCtxMenu.vu.username}</div>
			{#if hasChannelPermission(voiceCtxMenu.channelId, PermissionName.MUTE_MEMBERS)}
				<button class="voice-ctx-item" onclick={() => voiceCtxMenu && doServerMute(voiceCtxMenu.vu, voiceCtxMenu.channelId)}>
					{voiceCtxMenu.vu.server_mute ? 'Server Unmute' : 'Server Mute'}
				</button>
			{/if}
			{#if hasChannelPermission(voiceCtxMenu.channelId, PermissionName.DEAFEN_MEMBERS)}
				<button class="voice-ctx-item" onclick={() => voiceCtxMenu && doServerDeafen(voiceCtxMenu.vu, voiceCtxMenu.channelId)}>
					{voiceCtxMenu.vu.server_deaf ? 'Server Undeafen' : 'Server Deafen'}
				</button>
			{/if}
			{#if hasChannelPermission(voiceCtxMenu.channelId, PermissionName.MUTE_MEMBERS)}
				<button class="voice-ctx-item danger" onclick={() => voiceCtxMenu && doDisconnect(voiceCtxMenu.vu, voiceCtxMenu.channelId)}>
					Disconnect
				</button>
			{/if}
		</div>
	</div>
{/if}

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
	/* ===== Sidebar base ===== */
	.sidebar {
		width: 260px;
		min-width: 260px;
		background: var(--bg-surface);
		border-right: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		height: 100%;
	}

	/* Narrow + expanded: overlay */
	.sidebar.narrow.expanded {
		position: fixed;
		left: 0;
		top: 0;
		bottom: 0;
		z-index: 50;
		box-shadow: 4px 0 12px rgba(0, 0, 0, 0.2);
	}

	/* Narrow + collapsed: icon rail */
	.sidebar.collapsed {
		width: 56px;
		min-width: 56px;
	}

	/* ===== Icon rail ===== */
	.rail {
		display: flex;
		flex-direction: column;
		align-items: center;
		height: 100%;
		padding: 0.5rem 0;
		gap: 0.25rem;
	}

	.rail-server-icon {
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 0.25rem;
		border-radius: 8px;
		transition: background 0.15s;
		text-decoration: none;
		color: inherit;
	}

	.rail-server-icon:hover {
		background: var(--bg-hover);
	}

	.rail-divider {
		width: 32px;
		height: 1px;
		background: var(--border);
		margin: 0.25rem 0;
		flex-shrink: 0;
	}

	.rail-channels {
		flex: 1;
		overflow-y: auto;
		overflow-x: hidden;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 2px;
		padding: 0.25rem 0;
		width: 100%;
	}

	.rail-channel {
		position: relative;
		width: 40px;
		height: 40px;
		display: flex;
		align-items: center;
		justify-content: center;
		border: none;
		background: none;
		color: var(--text-muted);
		cursor: pointer;
		border-radius: 8px;
		transition: background 0.12s, color 0.12s;
	}

	.rail-channel:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.rail-channel.active {
		background: color-mix(in srgb, var(--accent) 12%, transparent);
		color: var(--accent);
	}

	.rail-channel.unread {
		color: var(--text);
	}

	.rail-channel.voice {
		opacity: 0.7;
	}

	.rail-channel.voice:hover,
	.rail-channel.voice-connected {
		opacity: 1;
	}

	.rail-channel.voice-connected .channel-icon {
		color: #2ecc71;
	}

	.rail-badge {
		position: absolute;
		top: 2px;
		right: 2px;
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--accent);
	}

	.rail-badge.mention {
		width: auto;
		height: auto;
		min-width: 14px;
		padding: 0 3px;
		font-size: 0.55rem;
		font-weight: 700;
		line-height: 14px;
		text-align: center;
		background: var(--danger);
		color: white;
		top: 0;
		right: 0;
	}

	/* Rail voice controls */
	.rail-voice {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.25rem;
		padding: 0.5rem 0;
		border-top: 1px solid var(--border);
		margin-top: auto;
	}

	.rail-footer {
		display: flex;
		flex-direction: column;
		align-items: center;
		padding: 0.5rem 0;
		border-top: 1px solid var(--border);
	}

	.rail-btn {
		background: none;
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.35rem;
		border-radius: 6px;
		display: flex;
		align-items: center;
		justify-content: center;
		transition: background 0.12s, color 0.12s;
	}

	.rail-btn:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	/* ===== Full sidebar ===== */
	/* Sidebar toggle row (mobile expanded) */
	.sidebar-toggle-row {
		padding: 0.5rem 0.75rem;
		border-bottom: 1px solid var(--border);
		flex-shrink: 0;
	}

	.sidebar-toggle-btn {
		background: none;
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.35rem;
		border-radius: 4px;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.sidebar-toggle-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	/* Server header */
	.server-header {
		padding: 0.75rem 0.75rem;
		border-bottom: 1px solid var(--border);
		flex-shrink: 0;
	}

	.server-header-link {
		display: flex;
		align-items: center;
		gap: 0.625rem;
		text-decoration: none;
		color: inherit;
		border-radius: 8px;
		padding: 0.375rem;
		transition: background 0.15s;
	}

	.server-header-link:hover {
		background: var(--bg-hover);
		text-decoration: none;
	}

	.server-icon {
		width: 32px;
		height: 32px;
		border-radius: 8px;
		object-fit: cover;
		flex-shrink: 0;
	}

	.server-icon-fallback {
		width: 32px;
		height: 32px;
		border-radius: 8px;
		background: var(--accent);
		color: white;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.875rem;
		font-weight: 700;
		flex-shrink: 0;
	}

	.server-info {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.server-name {
		font-weight: 600;
		font-size: 0.875rem;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.server-member-count {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	/* Connection banner */
	.connection-banner {
		padding: 0.4rem 0.75rem;
		font-size: 0.75rem;
		text-align: center;
		background: var(--danger);
		color: white;
	}

	.connection-banner.reconnecting {
		background: var(--warning, #f39c12);
	}

	/* Channel list */
	.channel-list {
		flex: 1;
		overflow-y: auto;
		padding: 0.5rem 0;
	}

	/* Category headers */
	.category-header {
		padding: 0.5rem 0.75rem;
		margin-top: 0.75rem;
		display: flex;
		align-items: center;
		gap: 0.35rem;
		cursor: pointer;
		border-radius: 6px;
		margin-left: 0.25rem;
		margin-right: 0.25rem;
		transition: background 0.12s;
	}

	.category-header:hover {
		background: var(--bg-hover);
	}

	.category-name {
		font-size: 0.65rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.08em;
		color: var(--text-muted);
	}

	/* Channel items */
	.channel-item {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		width: calc(100% - 1rem);
		padding: var(--channel-padding);
		border: none;
		background: none;
		color: var(--text-muted);
		font-size: var(--channel-font);
		cursor: pointer;
		text-align: left;
		border-radius: 8px;
		margin: 1px 0.5rem;
		transition: background 0.12s, color 0.12s, box-shadow 0.12s;
	}

	.channel-item:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.channel-item:hover .channel-icon {
		color: var(--accent);
	}

	.channel-item.active {
		background: color-mix(in srgb, var(--accent) 12%, transparent);
		color: var(--accent);
		box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--accent) 20%, transparent);
	}

	.channel-item.active .channel-icon {
		color: var(--accent);
	}

	.channel-item.unread {
		color: var(--text);
		font-weight: 600;
	}

	.channel-item.archived {
		opacity: 0.45;
	}

	.channel-item.archived:hover {
		opacity: 0.65;
	}

	.archived-badge {
		font-size: 0.55rem;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--text-muted);
		background: color-mix(in srgb, var(--text-muted) 15%, transparent);
		padding: 0.05rem 0.3rem;
		border-radius: 3px;
		flex-shrink: 0;
	}

	/* Voice channel specific */
	.channel-item.voice {
		opacity: 0.7;
	}

	.channel-item.voice:hover {
		opacity: 1;
	}

	.channel-item.voice:hover .channel-icon {
		color: #2ecc71;
	}

	.channel-item.voice.active {
		opacity: 1;
	}

	.channel-item.voice-connected {
		opacity: 1;
	}

	.channel-item.voice-connected .channel-icon,
	.channel-item.voice-connected.active .channel-icon {
		color: #2ecc71;
	}

	/* Channel icon (Material Symbols) */
	.channel-icon {
		font-size: 20px;
		line-height: 1;
		color: var(--text-muted);
		flex-shrink: 0;
		transition: color 0.12s;
	}

	.channel-icon-img {
		width: 20px;
		height: 20px;
		border-radius: 4px;
		object-fit: cover;
		flex-shrink: 0;
	}

	.channel-name {
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	/* Unread / mention badges */
	.unread-badge {
		background: color-mix(in srgb, var(--accent) 20%, transparent);
		color: var(--accent);
		font-size: 0.65rem;
		font-weight: 700;
		padding: 0.1rem 0.45rem;
		border-radius: 9999px;
		min-width: 1rem;
		text-align: center;
		line-height: 1.3;
	}

	.mention-badge {
		background: var(--danger);
		color: white;
		font-size: 0.65rem;
		font-weight: 700;
		padding: 0.1rem 0.45rem;
		border-radius: 9999px;
		min-width: 1rem;
		text-align: center;
		line-height: 1.3;
	}


	/* Footer */
	.sidebar-footer {
		padding: 0.5rem 0.75rem;
		border-top: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		position: relative;
	}

	.sidebar-create-btn {
		display: flex;
		align-items: center;
		gap: 0.3rem;
		padding: 0.35rem 0.5rem;
		font-size: 0.75rem;
		border: none;
		background: none;
		color: var(--text-muted);
		border-radius: 6px;
		cursor: pointer;
		transition: background 0.12s, color 0.12s;
	}

	.sidebar-create-btn:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.sidebar-theme-btn {
		position: absolute;
		right: 0.75rem;
		bottom: 0.5rem;
		background: none;
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.35rem;
		border-radius: 6px;
		display: flex;
		align-items: center;
		justify-content: center;
		transition: background 0.12s, color 0.12s;
	}

	.sidebar-theme-btn:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.chevron {
		font-size: 0.9rem;
		transition: transform 0.15s;
		display: inline-block;
		color: var(--text-muted);
		opacity: 0.6;
		flex-shrink: 0;
	}

	.chevron.collapsed {
		transform: rotate(-90deg);
	}

	/* Category rename */
	.category-rename-input {
		background: var(--bg-surface);
		color: var(--text);
		border: 1px solid var(--accent);
		border-radius: 4px;
		font-size: 0.65rem;
		font-weight: 700;
		text-transform: uppercase;
		letter-spacing: 0.08em;
		padding: 0.1rem 0.3rem;
		outline: none;
		min-width: 0;
		flex: 1;
	}

	.category-action {
		background: none;
		border: none;
		padding: 0.1rem;
		cursor: pointer;
		color: var(--text-muted);
		font-size: 0.7rem;
		opacity: 0;
		transition: opacity 0.15s;
		flex-shrink: 0;
	}

	.category-action.rename-btn {
		margin-left: auto;
	}

	.category-header:hover .category-action {
		opacity: 0.5;
	}

	.category-action:hover {
		opacity: 1 !important;
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
		color: var(--danger);
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
		box-shadow: 0 -2px 0 0 var(--accent);
	}

	.drop-after {
		box-shadow: 0 2px 0 0 var(--accent);
	}

	.drop-into {
		background: color-mix(in srgb, var(--accent) 15%, transparent) !important;
	}

	/* Voice channel users */
	.channel-wrapper {
		width: 100%;
	}

	.voice-users {
		padding: 0.15rem 0 0.15rem 2.5rem;
		margin: 0 0.5rem;
	}

	.clickable {
		cursor: pointer;
	}

	.clickable:hover {
		opacity: 0.8;
	}

	.voice-user {
		display: flex;
		align-items: center;
		gap: 0.3rem;
		padding: 0.1rem 0.4rem;
		font-size: 0.75rem;
		color: var(--text-muted);
		border-radius: 4px;
	}

	.voice-user:hover {
		background: var(--bg-hover);
	}

	.voice-user.muted {
		opacity: 0.7;
	}

	.voice-user.deafened {
		opacity: 0.5;
	}

	.voice-user.speaking {
		opacity: 1;
	}

	.voice-user.speaking .voice-avatar,
	.voice-user.speaking .voice-avatar-placeholder {
		outline: 2px solid #2ecc71;
		outline-offset: 1px;
	}

	.voice-avatar {
		width: 18px;
		height: 18px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.voice-avatar-placeholder {
		width: 18px;
		height: 18px;
		border-radius: 50%;
		background: var(--bg-active);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.6rem;
		font-weight: 600;
		color: var(--text-muted);
		flex-shrink: 0;
	}

	.voice-username {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
		flex: 1;
	}

	.voice-indicator {
		display: inline-flex;
		align-items: center;
		color: var(--text-muted);
		flex-shrink: 0;
	}

	.voice-indicator.server {
		color: var(--danger, #e74c3c);
	}

	.voice-user.overflow {
		color: var(--text-muted);
		font-size: 0.7rem;
		opacity: 0.7;
	}

	/* Voice status bar */
	.voice-bar {
		padding: 0.5rem 0.75rem;
		border-top: 1px solid var(--border);
		background: var(--bg-surface);
		border-radius: 8px 8px 0 0;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 0.5rem;
	}

	.voice-bar-info {
		display: flex;
		flex-direction: column;
		min-width: 0;
		flex: 1;
	}

	.voice-bar-label {
		font-size: 0.65rem;
		font-weight: 600;
		color: #2ecc71;
		text-transform: uppercase;
		letter-spacing: 0.03em;
	}

	.voice-bar-channel {
		font-size: 0.75rem;
		color: var(--text);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.voice-bar-controls {
		display: flex;
		gap: 0.25rem;
		flex-shrink: 0;
	}

	.voice-control-btn {
		background: none;
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.3rem;
		border-radius: 6px;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.voice-control-btn:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.voice-control-btn.active {
		color: var(--danger);
	}

	.voice-control-btn.active:hover {
		background: rgba(231, 76, 60, 0.15);
	}

	.voice-control-btn.disconnect-btn {
		color: var(--danger);
	}

	.voice-control-btn.disconnect-btn:hover {
		background: rgba(231, 76, 60, 0.15);
	}

	.ptt-indicator {
		font-size: 0.55rem;
		font-weight: 700;
		text-transform: uppercase;
		color: var(--text-muted);
		padding: 0.15rem 0.3rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		line-height: 1;
	}

	.ptt-indicator.active {
		color: #2ecc71;
		border-color: #2ecc71;
		background: rgba(46, 204, 113, 0.15);
	}

	.voice-control-btn:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}

	.voice-control-btn:disabled:hover {
		background: none;
	}

	.voice-control-btn.server-enforced {
		color: #e67e22;
	}

	.voice-error-banner {
		padding: 0.4rem 0.75rem;
		background: rgba(231, 76, 60, 0.15);
		color: #e74c3c;
		font-size: 0.7rem;
		text-align: center;
		border-top: 1px solid var(--border);
	}

	.e2ee-warn {
		font-size: 0.6rem;
		color: #f39c12;
		margin-left: 0.3rem;
		cursor: help;
	}

	/* Voice user context menu */
	.voice-ctx-overlay {
		position: fixed;
		inset: 0;
		z-index: 1000;
	}

	.voice-ctx-menu {
		position: fixed;
		background: var(--bg-surface, #2b2d31);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 0.3rem;
		min-width: 140px;
		box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
		z-index: 1001;
	}

	.voice-ctx-header {
		padding: 0.3rem 0.5rem;
		font-size: 0.7rem;
		font-weight: 600;
		color: var(--text-muted);
		border-bottom: 1px solid var(--border);
		margin-bottom: 0.2rem;
	}

	.voice-ctx-item {
		width: 100%;
		padding: 0.35rem 0.5rem;
		background: none;
		border: none;
		color: var(--text);
		font-size: 0.75rem;
		cursor: pointer;
		border-radius: 4px;
		text-align: left;
	}

	.voice-ctx-item:hover {
		background: var(--bg-hover);
	}

	.voice-ctx-item.danger {
		color: var(--danger);
	}

	.voice-ctx-item.danger:hover {
		background: rgba(231, 76, 60, 0.15);
	}

	/* DM section */
	.dm-section {
		border-top: 1px solid var(--border);
		padding: 0.5rem 0;
	}

	.dm-section-header {
		display: flex;
		align-items: center;
		gap: 0.35rem;
		padding: 0.4rem 0.75rem;
		text-decoration: none;
		color: inherit;
		border-radius: 6px;
		margin: 0 0.25rem;
		transition: background 0.12s;
	}

	.dm-section-header:hover {
		background: var(--bg-hover);
		text-decoration: none;
	}

	.dm-total-badge {
		background: var(--accent);
		color: white;
		font-size: 0.55rem;
		font-weight: 700;
		padding: 0.05rem 0.3rem;
		border-radius: 9999px;
		min-width: 0.9rem;
		text-align: center;
		line-height: 1.3;
		margin-left: auto;
	}

	.dm-item {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.3rem 0.75rem;
		margin: 1px 0.5rem;
		border-radius: 8px;
		text-decoration: none;
		color: var(--text-muted);
		font-size: var(--channel-font, 0.85rem);
		transition: background 0.12s, color 0.12s;
	}

	.dm-item:hover {
		background: var(--bg-hover);
		color: var(--text);
		text-decoration: none;
	}

	.dm-item.unread {
		color: var(--text);
		font-weight: 600;
	}

	.dm-avatar {
		width: 22px;
		height: 22px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.dm-avatar-placeholder {
		width: 22px;
		height: 22px;
		border-radius: 50%;
		background: var(--bg-active, #3a3d41);
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.65rem;
		font-weight: 600;
		color: var(--text-muted);
		flex-shrink: 0;
	}

	.dm-name {
		flex: 1;
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}
</style>
