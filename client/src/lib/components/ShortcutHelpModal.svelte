<script lang="ts">
	import { isShortcutHelpOpen, closeShortcutHelp, shortcuts } from '$lib/stores/shortcuts.svelte.js';

	let open = $derived(isShortcutHelpOpen());

	function onOverlayClick(e: MouseEvent) {
		if (e.target === e.currentTarget) closeShortcutHelp();
	}

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') closeShortcutHelp();
	}
</script>

{#if open}
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<div class="overlay" onclick={onOverlayClick} onkeydown={onKeydown} role="dialog" aria-modal="true" tabindex="-1">
		<div class="modal">
			<div class="header">
				<h3>Keyboard Shortcuts</h3>
				<button class="close-btn" onclick={closeShortcutHelp}>✕</button>
			</div>
			<div class="shortcut-list">
				{#each shortcuts as s}
					<div class="shortcut-row">
						<span class="shortcut-desc">{s.description}</span>
						<span class="shortcut-keys">
							{#each s.keys.split('+') as part, i}
								{#if i > 0}<span class="plus">+</span>{/if}
								<kbd>{part}</kbd>
							{/each}
						</span>
					</div>
				{/each}
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
		align-items: center;
		justify-content: center;
		z-index: 1000;
	}

	.modal {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 12px;
		width: 100%;
		max-width: 420px;
		overflow: hidden;
	}

	.header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 1rem 1.25rem;
		border-bottom: 1px solid var(--border);
	}

	.header h3 {
		margin: 0;
		font-size: 1rem;
	}

	.close-btn {
		background: none;
		border: none;
		color: var(--text-muted);
		cursor: pointer;
		font-size: 0.9rem;
		padding: 0.25rem;
	}

	.close-btn:hover {
		color: var(--text);
	}

	.shortcut-list {
		padding: 0.75rem 1.25rem;
	}

	.shortcut-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.45rem 0;
	}

	.shortcut-row + .shortcut-row {
		border-top: 1px solid var(--border);
	}

	.shortcut-desc {
		font-size: 0.85rem;
		color: var(--text);
	}

	.shortcut-keys {
		display: flex;
		align-items: center;
		gap: 0.2rem;
	}

	.plus {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	kbd {
		font-size: 0.72rem;
		font-family: inherit;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 4px;
		padding: 0.15rem 0.45rem;
		color: var(--text-muted);
		min-width: 1.4rem;
		text-align: center;
	}
</style>
