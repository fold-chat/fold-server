<script lang="ts">
	import { logout } from '$lib/api/auth.js';
	import { reset, getServerName } from '$lib/stores/auth.svelte.js';
	import { disconnect } from '$lib/stores/ws.svelte.js';
	import { isSearchOpen, closeSearch, toggleSearch } from '$lib/stores/search.svelte.js';
	import { getChannels, getTotalMentionCount } from '$lib/stores/channels.svelte.js';
	import { getActiveChannelId } from '$lib/stores/messages.svelte.js';
	import { isShortcutHelpOpen, closeShortcutHelp, toggleShortcutHelp } from '$lib/stores/shortcuts.svelte.js';
	import { goto } from '$app/navigation';
	import Sidebar from '$lib/components/Sidebar.svelte';
	import SearchModal from '$lib/components/SearchModal.svelte';
	import ShortcutHelpModal from '$lib/components/ShortcutHelpModal.svelte';

	let { children } = $props();

	$effect(() => {
		const count = getTotalMentionCount();
		const name = getServerName();
		document.title = count > 0 ? `(${count}) ${name}` : name;
	});

	async function handleLogout() {
		disconnect();
		try {
			await logout();
		} catch {
			// ignore
		}
		reset();
		goto('/login');
	}

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

	function onKeydown(e: KeyboardEvent) {
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
			window.dispatchEvent(new CustomEvent('fray:toggle-emoji'));
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

<svelte:window onkeydown={onKeydown} />

<div class="app-shell">
	<Sidebar />
	<main class="main-content">
		{@render children()}
	</main>
</div>

<SearchModal />
<ShortcutHelpModal />

<style>
	.app-shell {
		display: flex;
		height: 100vh;
		overflow: hidden;
	}

	.main-content {
		flex: 1;
		display: flex;
		flex-direction: column;
		min-width: 0;
	}
</style>
