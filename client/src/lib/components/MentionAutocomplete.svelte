<script lang="ts">
interface AutocompleteItem {
	type: 'user' | 'role' | 'everyone';
	id?: string;
	name: string;
	displayName?: string;
	color?: string;
}

let {
	items,
	selectedIndex = 0,
	onSelect
}: {
	items: AutocompleteItem[];
	selectedIndex?: number;
	onSelect: (item: { type: 'user' | 'role' | 'everyone'; id?: string; name: string }) => void;
} = $props();

function handleSelect(item: AutocompleteItem) {
	onSelect({ type: item.type, id: item.id, name: item.name });
}
</script>

{#if items.length > 0}
	<div class="mention-autocomplete">
		{#each items as item, i}
			<!-- svelte-ignore a11y_no_static_element_interactions -->
			<div
				class="mention-item"
				class:selected={i === selectedIndex}
				onclick={() => handleSelect(item)}
			>
				{#if item.type === 'user'}
					<span class="mention-icon">👤</span>
					<span class="mention-name">{item.displayName}</span>
					<span class="mention-username">@{item.name}</span>
				{:else if item.type === 'role'}
					<span class="mention-icon" style:color={item.color ?? 'inherit'}>●</span>
					<span class="mention-name" style:color={item.color ?? 'inherit'}>@{item.name}</span>
				{:else}
					<span class="mention-icon">@</span>
					<span class="mention-name">@everyone</span>
				{/if}
			</div>
		{/each}
	</div>
{/if}

<style>
	.mention-autocomplete {
		position: absolute;
		bottom: 100%;
		left: 0;
		right: 0;
		background: var(--bg-surface, #16213e);
		border: 1px solid var(--border, #2a2a4a);
		border-radius: 8px;
		max-height: 300px;
		overflow-y: auto;
		box-shadow: 0 -4px 12px rgba(0, 0, 0, 0.3);
		z-index: 100;
		margin-bottom: 4px;
	}

	.mention-item {
		padding: 0.5rem 0.75rem;
		display: flex;
		align-items: center;
		gap: 0.5rem;
		cursor: pointer;
		transition: background 0.1s;
	}

	.mention-item:hover,
	.mention-item.selected {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.mention-icon {
		font-size: 1rem;
		flex-shrink: 0;
	}

	.mention-name {
		font-weight: 500;
		flex-shrink: 0;
	}

	.mention-username {
		color: var(--text-muted, #888);
		font-size: 0.9rem;
	}
</style>
