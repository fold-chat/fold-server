<script lang="ts">
	import { searchMedia, trendingMedia, type MediaItem } from '$lib/api/media.js';
	import { tick } from 'svelte';

	let { onSelect, onClose }: { onSelect: (url: string, title: string) => void; onClose: () => void } = $props();

	let query = $state('');
	let tab = $state<'trending' | 'search'>('trending');
	let items = $state<MediaItem[]>([]);
	let nextCursor = $state<string | null>(null);
	let loading = $state(false);
	let searchInput = $state<HTMLInputElement | null>(null);
	let pickerEl = $state<HTMLDivElement | null>(null);
	let gridEl = $state<HTMLDivElement | null>(null);
	let searchTimeout = $state<ReturnType<typeof setTimeout> | null>(null);

	$effect(() => {
		tick().then(() => searchInput?.focus());
	});

	$effect(() => {
		function handleClickOutside(e: MouseEvent) {
			if (pickerEl && !pickerEl.contains(e.target as Node)) {
				onClose();
			}
		}
		function handleKeydown(e: KeyboardEvent) {
			if (e.key === 'Escape') onClose();
		}
		document.addEventListener('mousedown', handleClickOutside);
		document.addEventListener('keydown', handleKeydown);
		return () => {
			document.removeEventListener('mousedown', handleClickOutside);
			document.removeEventListener('keydown', handleKeydown);
		};
	});

	// Load trending on mount
	$effect(() => {
		loadTrending();
	});

	async function loadTrending() {
		tab = 'trending';
		query = '';
		items = [];
		nextCursor = null;
		loading = true;
		try {
			const res = await trendingMedia('gif', 20);
			items = res.results;
			nextCursor = res.next;
		} catch { /* ignore */ }
		loading = false;
	}

	function handleSearchInput() {
		if (searchTimeout) clearTimeout(searchTimeout);
		const q = query.trim();
		if (!q) {
			loadTrending();
			return;
		}
		tab = 'search';
		searchTimeout = setTimeout(() => doSearch(q), 300);
	}

	async function doSearch(q: string) {
		items = [];
		nextCursor = null;
		loading = true;
		try {
			const res = await searchMedia(q, 'gif', 20);
			items = res.results;
			nextCursor = res.next;
		} catch { /* ignore */ }
		loading = false;
	}

	async function loadMore() {
		if (loading || !nextCursor) return;
		loading = true;
		try {
			const res = tab === 'search' && query.trim()
				? await searchMedia(query.trim(), 'gif', 20, nextCursor)
				: await trendingMedia('gif', 20, nextCursor);
			items = [...items, ...res.results];
			nextCursor = res.next;
		} catch { /* ignore */ }
		loading = false;
	}

	function handleScroll() {
		if (!gridEl) return;
		const { scrollTop, scrollHeight, clientHeight } = gridEl;
		if (scrollHeight - scrollTop - clientHeight < 200) {
			loadMore();
		}
	}

	function select(item: MediaItem) {
		onSelect(item.url, item.title);
		onClose();
	}
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="gif-picker" bind:this={pickerEl}>
	<div class="picker-search">
		<input
			type="text"
			bind:this={searchInput}
			bind:value={query}
			oninput={handleSearchInput}
			placeholder="Search KLIPY..."
			class="search-input"
		/>
	</div>
	<div class="tab-bar">
		<button class="tab-btn" class:active={tab === 'trending'} onclick={loadTrending}>Trending</button>
		<button class="tab-btn" class:active={tab === 'search'} disabled={!query.trim()} onclick={() => query.trim() && doSearch(query.trim())}>Search</button>
	</div>
	<div class="gif-grid-container" bind:this={gridEl} onscroll={handleScroll}>
		{#if items.length === 0 && !loading}
			<div class="no-results">{tab === 'search' ? 'No GIFs found' : 'Loading...'}</div>
		{:else}
			<div class="gif-grid">
				{#each items as item (item.id)}
					<button class="gif-item" onclick={() => select(item)} title={item.title}>
						<img
							src={item.preview_url || item.url}
							alt={item.title}
							loading="lazy"
							style="aspect-ratio: {item.width}/{item.height || 1}"
						/>
					</button>
				{/each}
			</div>
		{/if}
		{#if loading}
			<div class="loading">Loading...</div>
		{/if}
	</div>
	<div class="attribution">
		Powered by <a href="https://klipy.com" target="_blank" rel="noopener noreferrer">KLIPY</a>
	</div>
</div>

<style>
	.gif-picker {
		width: 380px;
		max-height: 420px;
		display: flex;
		flex-direction: column;
		background: var(--bg-surface, #2b2d31);
		border: 1px solid var(--border);
		border-radius: 8px;
		box-shadow: 0 4px 16px rgba(0, 0, 0, 0.4);
		z-index: 100;
		overflow: hidden;
	}

	.picker-search {
		padding: 0.5rem;
		flex-shrink: 0;
	}

	.search-input {
		width: 100%;
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.4rem 0.6rem;
		font-size: 0.8rem;
		font-family: inherit;
	}

	.search-input:focus {
		outline: none;
		border-color: var(--accent, #5865f2);
	}

	.search-input::placeholder {
		color: var(--text-muted);
	}

	.tab-bar {
		display: flex;
		border-bottom: 1px solid var(--border);
		flex-shrink: 0;
	}

	.tab-btn {
		flex: 1;
		background: none;
		border: none;
		border-bottom: 2px solid transparent;
		cursor: pointer;
		font-size: 0.8rem;
		color: var(--text-muted);
		padding: 0.4rem 0;
		transition: color 0.1s;
	}

	.tab-btn:hover:not(:disabled) {
		color: var(--text);
	}

	.tab-btn.active {
		color: var(--text);
		border-bottom-color: var(--accent, #5865f2);
	}

	.tab-btn:disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}

	.gif-grid-container {
		flex: 1;
		overflow-y: auto;
		padding: 0.25rem;
	}

	.gif-grid {
		display: grid;
		grid-template-columns: repeat(2, 1fr);
		gap: 4px;
	}

	.gif-item {
		background: none;
		border: none;
		cursor: pointer;
		padding: 0;
		border-radius: 4px;
		overflow: hidden;
		line-height: 0;
	}

	.gif-item:hover {
		outline: 2px solid var(--accent, #5865f2);
	}

	.gif-item img {
		width: 100%;
		height: auto;
		object-fit: cover;
		border-radius: 4px;
		min-height: 60px;
		background: var(--bg, #1e1f22);
	}

	.no-results {
		text-align: center;
		color: var(--text-muted);
		padding: 2rem;
		font-size: 0.8rem;
	}

	.loading {
		text-align: center;
		color: var(--text-muted);
		padding: 0.75rem;
		font-size: 0.75rem;
	}

	.attribution {
		text-align: center;
		color: var(--text-muted);
		font-size: 0.65rem;
		padding: 0.3rem;
		border-top: 1px solid var(--border);
		flex-shrink: 0;
	}

	.attribution a {
		color: var(--text-muted);
		text-decoration: none;
		font-weight: 600;
	}

	.attribution a:hover {
		color: var(--text);
	}
</style>
