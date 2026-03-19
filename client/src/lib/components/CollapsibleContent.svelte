<script lang="ts">
	import type { Snippet } from 'svelte';

	let { children, disabled = false }: { children: Snippet; disabled?: boolean } = $props();

	let expanded = $state(false);
	let overflows = $state(false);

	function collapsible(node: HTMLElement) {
		function check() {
			if (overflows || expanded) return;
			if (node.scrollHeight > node.clientHeight + 2) overflows = true;
		}
		requestAnimationFrame(check);
		node.addEventListener('load', check, true);
		return { destroy() { node.removeEventListener('load', check, true); } };
	}
</script>

{#if disabled}
	{@render children()}
{:else}
<div class="collapsible" class:expanded class:overflows use:collapsible>
	{@render children()}
</div>
{#if overflows}
	<button class="expand-toggle" onclick={() => expanded = !expanded}>
		{expanded ? 'Show less' : 'Show more'}
	</button>
{/if}
{/if}

<style>
	.collapsible {
		max-height: 21em;
		overflow: hidden;
	}

	.collapsible.expanded {
		max-height: none;
		overflow: visible;
	}

	.collapsible.overflows:not(.expanded) {
		-webkit-mask-image: linear-gradient(to bottom, black 70%, transparent);
		mask-image: linear-gradient(to bottom, black 70%, transparent);
	}

	.expand-toggle {
		background: none;
		border: none;
		color: var(--accent, #5865f2);
		font-size: 0.78rem;
		cursor: pointer;
		padding: 0.15rem 0;
	}

	.expand-toggle:hover {
		text-decoration: underline;
		background: none;
	}
</style>
