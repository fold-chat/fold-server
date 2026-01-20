<script lang="ts">
	import { categories, searchEmoji, type Emoji } from '$lib/data/emoji.js';
	import { tick } from 'svelte';

	let { onSelect, onClose }: { onSelect: (emoji: string) => void; onClose: () => void } = $props();

	let query = $state('');
	let activeCategory = $state(categories[0].id);
	let searchInput = $state<HTMLInputElement | null>(null);
	let pickerEl = $state<HTMLDivElement | null>(null);

	let searchResults = $derived(query ? searchEmoji(query) : null);

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

	function select(emoji: string) {
		onSelect(emoji);
		onClose();
	}

	function scrollToCategory(id: string) {
		activeCategory = id;
		query = '';
		const el = pickerEl?.querySelector(`[data-category="${id}"]`);
		el?.scrollIntoView({ behavior: 'smooth', block: 'start' });
	}
</script>

<!-- svelte-ignore a11y_no_static_element_interactions -->
<div class="emoji-picker" bind:this={pickerEl}>
	<div class="picker-search">
		<input
			type="text"
			bind:this={searchInput}
			bind:value={query}
			placeholder="Search emoji..."
			class="search-input"
		/>
	</div>
	<div class="category-tabs">
		{#each categories as cat}
			<button
				class="cat-tab"
				class:active={activeCategory === cat.id && !searchResults}
				onclick={() => scrollToCategory(cat.id)}
				title={cat.name}
			>{cat.icon}</button>
		{/each}
	</div>
	<div class="emoji-grid-container">
		{#if searchResults}
			{#if searchResults.length === 0}
				<div class="no-results">No emoji found</div>
			{:else}
				<div class="emoji-grid">
					{#each searchResults.slice(0, 80) as e}
						<button class="emoji-btn" onclick={() => select(e.emoji)} title={e.name}>{e.emoji}</button>
					{/each}
				</div>
			{/if}
		{:else}
			{#each categories as cat}
				<div data-category={cat.id}>
					<div class="category-label">{cat.name}</div>
					<div class="emoji-grid">
						{#each cat.emojis as e}
							<button class="emoji-btn" onclick={() => select(e.emoji)} title={e.name}>{e.emoji}</button>
						{/each}
					</div>
				</div>
			{/each}
		{/if}
	</div>
</div>

<style>
	.emoji-picker {
		width: 320px;
		max-height: 360px;
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

	.category-tabs {
		display: flex;
		gap: 0;
		padding: 0 0.25rem;
		border-bottom: 1px solid var(--border);
		flex-shrink: 0;
	}

	.cat-tab {
		flex: 1;
		background: none;
		border: none;
		border-bottom: 2px solid transparent;
		cursor: pointer;
		font-size: 1rem;
		padding: 0.3rem 0;
		opacity: 0.5;
		transition: opacity 0.1s;
	}

	.cat-tab:hover {
		opacity: 0.8;
	}

	.cat-tab.active {
		opacity: 1;
		border-bottom-color: var(--accent, #5865f2);
	}

	.emoji-grid-container {
		flex: 1;
		overflow-y: auto;
		padding: 0.25rem 0.5rem;
	}

	.category-label {
		font-size: 0.7rem;
		color: var(--text-muted);
		font-weight: 600;
		text-transform: uppercase;
		padding: 0.4rem 0 0.2rem;
		position: sticky;
		top: 0;
		background: var(--bg-surface, #2b2d31);
	}

	.emoji-grid {
		display: grid;
		grid-template-columns: repeat(8, 1fr);
		gap: 0;
	}

	.emoji-btn {
		background: none;
		border: none;
		cursor: pointer;
		font-size: 1.3rem;
		padding: 0.2rem;
		border-radius: 4px;
		line-height: 1;
		text-align: center;
	}

	.emoji-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.1));
	}

	.no-results {
		text-align: center;
		color: var(--text-muted);
		padding: 2rem;
		font-size: 0.8rem;
	}
</style>
