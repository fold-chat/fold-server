<script lang="ts">
	import { getServerName } from '$lib/stores/auth.svelte.js';
	import { isSearchOpen, closeSearch, toggleSearch } from '$lib/stores/search.svelte.js';
	import { getChannels, getTotalMentionCount } from '$lib/stores/channels.svelte.js';
	import { getActiveChannelId } from '$lib/stores/messages.svelte.js';
	import { isShortcutHelpOpen, closeShortcutHelp, toggleShortcutHelp } from '$lib/stores/shortcuts.svelte.js';
	import { isPttEnabled, getPttKey, pttKeyDown, pttKeyUp, getCurrentVoiceChannelId, getJoiningChannelId } from '$lib/stores/voice.svelte.js';
	import { isNotificationPanelOpen } from '$lib/stores/notifications.svelte.js';
	import { isNarrowScreen, isSidebarExpanded, closeSidebar } from '$lib/stores/sidebar.svelte.js';
	import { goto } from '$app/navigation';
	import Sidebar from '$lib/components/Sidebar.svelte';
	import TopBar from '$lib/components/TopBar.svelte';
	import SearchModal from '$lib/components/SearchModal.svelte';
	import ShortcutHelpModal from '$lib/components/ShortcutHelpModal.svelte';
	import NotificationPanel from '$lib/components/NotificationPanel.svelte';
	import MembersPanel from '$lib/components/MembersPanel.svelte';
	import VoiceBar from '$lib/components/VoiceBar.svelte';
	import VersionMismatchBanner from '$lib/components/VersionMismatchBanner.svelte';
	import { isMembersPanelOpen } from '$lib/stores/membersPanel.svelte.js';

	let { children } = $props();

	$effect(() => {
		const count = getTotalMentionCount();
		const name = getServerName();
		document.title = count > 0 ? `(${count}) ${name}` : name;
	});

	function isTyping(e: KeyboardEvent): boolean {
		const tag = (e.target as HTMLElement)?.tagName;
		const editable = (e.target as HTMLElement)?.isContentEditable;
		return tag === 'INPUT' || tag === 'TEXTAREA' || editable === true;
	}

	function navigateChannel(direction: -1 | 1) {
		const channels = getChannels();
		if (channels.length === 0) return;
		const activeId = getActiveChannelId();
		const idx = activeId ? channels.findIndex((c) => c.id === activeId) : -1;
		const next = idx === -1
			? (direction === 1 ? 0 : channels.length - 1)
			: Math.min(Math.max(idx + direction, 0), channels.length - 1);
		if (channels[next]) goto(`/channels/${channels[next].id}`);
	}

	function onKeyup(e: KeyboardEvent) {
		if (isPttEnabled() && getPttKey() && e.key === getPttKey()) {
			pttKeyUp();
		}
	}

	function onKeydown(e: KeyboardEvent) {
		// Push-to-talk
		if (isPttEnabled() && getPttKey() && e.key === getPttKey() && !e.repeat) {
			pttKeyDown();
			return;
		}

		const mod = e.metaKey || e.ctrlKey;

		// Escape always works, even in inputs
		if (e.key === 'Escape') {
			if (isShortcutHelpOpen()) { closeShortcutHelp(); e.preventDefault(); return; }
			if (isSearchOpen()) { closeSearch(); e.preventDefault(); return; }
			return;
		}

		// Skip remaining shortcuts when typing in an input
		if (isTyping(e)) return;

		if (mod && e.key === 'k') {
			e.preventDefault();
			toggleSearch();
			return;
		}

		if (mod && e.key === '/') {
			e.preventDefault();
			toggleShortcutHelp();
			return;
		}

		if (e.key === '?' && !mod && !e.shiftKey) {
			e.preventDefault();
			toggleShortcutHelp();
			return;
		}

		if (mod && e.key === 'e') {
			e.preventDefault();
			// Dispatch custom event for MessageCompose to toggle emoji picker
			window.dispatchEvent(new CustomEvent('fold:toggle-emoji'));
			return;
		}

		if (e.altKey && e.key === 'ArrowUp') {
			e.preventDefault();
			navigateChannel(-1);
			return;
		}

		if (e.altKey && e.key === 'ArrowDown') {
			e.preventDefault();
			navigateChannel(1);
			return;
		}
	}
</script>

<svelte:window onkeydown={onKeydown} onkeyup={onKeyup} />

<div class="app-shell">
	<Sidebar />
	{#if isNarrowScreen() && isSidebarExpanded()}
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="sidebar-backdrop" onclick={closeSidebar} onkeydown={(e) => { if (e.key === 'Escape') closeSidebar(); }}></div>
	{/if}
	<div class="app-right">
		<VersionMismatchBanner />
		<TopBar />
		<div class="app-body">
			<main class="main-content">
				{@render children()}
			</main>
			{#if isNotificationPanelOpen()}
				<NotificationPanel />
			{/if}
			{#if isMembersPanelOpen()}
				<MembersPanel />
			{/if}
		</div>
	</div>
</div>

<SearchModal />
<ShortcutHelpModal />

{#if isNarrowScreen() && (getCurrentVoiceChannelId() || getJoiningChannelId())}
	<VoiceBar />
{/if}

<style>
	.app-shell {
		display: flex;
		height: 100vh;
		overflow: hidden;
	}

	.app-right {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.app-body {
		flex: 1;
		display: flex;
		min-height: 0;
	}

	.main-content {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.sidebar-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.4);
		z-index: 49;
	}
</style>
