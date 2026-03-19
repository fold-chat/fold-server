<script lang="ts">
	import { goto } from '$app/navigation';
	import { getChannels } from '$lib/stores/channels.svelte.js';
import {
		isSearchOpen,
		closeSearch,
		getSearchQuery,
		setSearchQuery,
		getSearchResults,
		getSearchFilters,
		isSearchLoading,
		getSearchError,
		updateFilter,
		removeFilter
	} from '$lib/stores/search.svelte.js';
	import { isDmChannel } from '$lib/stores/dm.svelte.js';
	import type { SearchFilters } from '$lib/api/search.js';

	let inputEl: HTMLInputElement | undefined = $state();

	let open = $derived(isSearchOpen());
	let query = $derived(getSearchQuery());
	let results = $derived(getSearchResults());
	let filters = $derived(getSearchFilters());
	let loading = $derived(isSearchLoading());
	let error = $derived(getSearchError());
	let channels = $derived(getChannels());

	// Filter dropdown state
	let showChannelFilter = $state(false);
	let showHasFilter = $state(false);

	$effect(() => {
		if (open) {
			// Focus input when modal opens
			setTimeout(() => inputEl?.focus(), 0);
		}
	});

	function onOverlayClick(e: MouseEvent) {
		if (e.target === e.currentTarget) closeSearch();
	}

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') {
			closeSearch();
		}
	}

	function channelName(channelId: string): string {
		if (isDmChannel(channelId)) return 'Direct Message';
		return channels.find((c) => c.id === channelId)?.name ?? channelId;
	}

	function formatTime(dateStr: string): string {
		const d = new Date(dateStr + 'Z');
		return d.toLocaleDateString(undefined, { month: 'short', day: 'numeric', year: 'numeric' });
	}

	function navigateToMessage(channelId: string, messageId: string, threadId: string | null) {
		closeSearch();
		if (isDmChannel(channelId)) {
			goto(`/dm/${channelId}`);
		} else if (threadId) {
			goto(`/channels/${channelId}/threads/${threadId}?highlight=${messageId}`);
		} else {
			goto(`/channels/${channelId}?around=${messageId}`);
		}
	}

	function toggleChannelFilter() {
		showChannelFilter = !showChannelFilter;
		showHasFilter = false;
	}

	function toggleHasFilter() {
		showHasFilter = !showHasFilter;
		showChannelFilter = false;
	}

	function selectChannel(id: string) {
		updateFilter('channel_id', id);
		showChannelFilter = false;
	}

	function selectHas(value: SearchFilters['has']) {
		updateFilter('has', value);
		showHasFilter = false;
	}
</script>

{#if open}
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<div class="overlay" onclick={onOverlayClick} onkeydown={onKeydown} role="dialog" aria-modal="true" tabindex="-1">
		<div class="search-modal">
			<div class="search-input-row">
				<svg class="search-icon" viewBox="0 0 20 20" fill="currentColor" width="18" height="18">
					<path fill-rule="evenodd" d="M9 3.5a5.5 5.5 0 100 11 5.5 5.5 0 000-11zM2 9a7 7 0 1112.45 4.39l4.26 4.26a.75.75 0 11-1.06 1.06l-4.26-4.26A7 7 0 012 9z" clip-rule="evenodd" />
				</svg>
				<input
					bind:this={inputEl}
					type="text"
					placeholder="Search messages…"
					value={query}
					oninput={(e) => setSearchQuery(e.currentTarget.value)}
				/>
				<kbd class="esc-hint">ESC</kbd>
			</div>

			<div class="filters">
				{#if filters.channel_id}
					<button class="chip active" onclick={() => removeFilter('channel_id')}>
						# {channelName(filters.channel_id)} ✕
					</button>
				{:else}
					<div class="filter-dropdown">
						<button class="chip" onclick={toggleChannelFilter}>Channel</button>
						{#if showChannelFilter}
							<div class="dropdown">
								{#each channels as ch}
									<button class="dropdown-item" onclick={() => selectChannel(ch.id)}>
										# {ch.name}
									</button>
								{/each}
							</div>
						{/if}
					</div>
				{/if}

				{#if filters.has}
					<button class="chip active" onclick={() => removeFilter('has')}>
						has:{filters.has} ✕
					</button>
				{:else}
					<div class="filter-dropdown">
						<button class="chip" onclick={toggleHasFilter}>Has…</button>
						{#if showHasFilter}
							<div class="dropdown">
								<button class="dropdown-item" onclick={() => selectHas('file')}>File</button>
								<button class="dropdown-item" onclick={() => selectHas('image')}>Image</button>
							</div>
						{/if}
					</div>
				{/if}
			</div>

			<div class="results">
				{#if loading}
					<div class="status">Searching…</div>
				{:else if error}
					<div class="status error">{error}</div>
				{:else if !query.trim()}
					<div class="status">
						<svg viewBox="0 0 20 20" fill="currentColor" width="32" height="32" class="empty-icon">
							<path fill-rule="evenodd" d="M9 3.5a5.5 5.5 0 100 11 5.5 5.5 0 000-11zM2 9a7 7 0 1112.45 4.39l4.26 4.26a.75.75 0 11-1.06 1.06l-4.26-4.26A7 7 0 012 9z" clip-rule="evenodd" />
						</svg>
						<p>Search messages across all channels</p>
					</div>
				{:else if results.length === 0}
					<div class="status">No results found</div>
				{:else}
					{#each results as result}
						<button
							class="result-card"
							onclick={() => navigateToMessage(result.channel_id, result.id, result.thread_id)}
						>
							<div class="result-header">
								<span class="result-author">
									{result.author_display_name ?? result.author_username ?? 'Unknown'}
								</span>
						<span class="result-meta">
							<span class="result-channel">{isDmChannel(result.channel_id) ? '' : '# '}{channelName(result.channel_id)}</span>
									<span class="result-time">{formatTime(result.created_at)}</span>
								</span>
							</div>
							<div class="result-snippet">{@html result.snippet}</div>
						</button>
					{/each}
				{/if}
			</div>
		</div>
	</div>
{/if}

<style>
	.overlay {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		display: flex;
		align-items: flex-start;
		justify-content: center;
		padding-top: 10vh;
		z-index: 1000;
	}

	.search-modal {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 12px;
		width: 100%;
		max-width: 600px;
		max-height: 70vh;
		display: flex;
		flex-direction: column;
		overflow: hidden;
	}

	.search-input-row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.75rem 1rem;
		border-bottom: 1px solid var(--border);
	}

	.search-icon {
		color: var(--text-muted);
		flex-shrink: 0;
	}

	.search-input-row input {
		flex: 1;
		background: none;
		border: none;
		color: var(--text);
		font-size: 0.95rem;
		outline: none;
		padding: 0;
	}

	.esc-hint {
		font-size: 0.65rem;
		color: var(--text-muted);
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 3px;
		padding: 0.15rem 0.4rem;
		flex-shrink: 0;
	}

	.filters {
		display: flex;
		gap: 0.35rem;
		padding: 0.5rem 1rem;
		border-bottom: 1px solid var(--border);
		flex-wrap: wrap;
	}

	.chip {
		font-size: 0.72rem;
		padding: 0.2rem 0.55rem;
		border-radius: 4px;
		border: 1px solid var(--border);
		background: var(--bg);
		color: var(--text-muted);
		cursor: pointer;
		white-space: nowrap;
	}

	.chip:hover {
		color: var(--text);
		border-color: var(--text-muted);
	}

	.chip.active {
		background: var(--accent);
		color: white;
		border-color: var(--accent);
	}

	.filter-dropdown {
		position: relative;
	}

	.dropdown {
		position: absolute;
		top: 100%;
		left: 0;
		margin-top: 0.25rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 6px;
		max-height: 200px;
		overflow-y: auto;
		min-width: 160px;
		z-index: 10;
	}

	.dropdown-item {
		display: block;
		width: 100%;
		text-align: left;
		padding: 0.4rem 0.75rem;
		font-size: 0.8rem;
		background: none;
		border: none;
		border-radius: 0;
		color: var(--text);
		cursor: pointer;
	}

	.dropdown-item:hover {
		background: var(--bg-input);
	}

	.results {
		flex: 1;
		overflow-y: auto;
		padding: 0.5rem;
	}

	.status {
		text-align: center;
		padding: 2rem 1rem;
		color: var(--text-muted);
		font-size: 0.85rem;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.5rem;
	}

	.status p {
		margin: 0;
	}

	.empty-icon {
		opacity: 0.3;
	}

	.status.error {
		color: var(--error);
	}

	.result-card {
		display: block;
		width: 100%;
		text-align: left;
		padding: 0.6rem 0.75rem;
		border-radius: 6px;
		background: none;
		border: none;
		color: var(--text);
		cursor: pointer;
		font-size: 0.85rem;
	}

	.result-card:hover {
		background: var(--bg-input);
	}

	.result-card + .result-card {
		margin-top: 0.15rem;
	}

	.result-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 0.2rem;
	}

	.result-author {
		font-weight: 600;
		font-size: 0.8rem;
	}

	.result-meta {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}

	.result-channel {
		font-size: 0.72rem;
		color: var(--text-muted);
	}

	.result-time {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.result-snippet {
		color: var(--text-muted);
		font-size: 0.8rem;
		line-height: 1.4;
		overflow: hidden;
		text-overflow: ellipsis;
		display: -webkit-box;
		-webkit-line-clamp: 2;
		line-clamp: 2;
		-webkit-box-orient: vertical;
	}

	.result-snippet :global(mark) {
		background: var(--accent);
		color: white;
		border-radius: 2px;
		padding: 0 0.1rem;
	}
</style>
