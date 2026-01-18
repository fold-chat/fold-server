<script lang="ts">
	let {
		open = false,
		title = 'Confirm',
		message = 'Are you sure?',
		confirmLabel = 'Delete',
		cancelLabel = 'Cancel',
		danger = true,
		onconfirm,
		oncancel
	}: {
		open: boolean;
		title?: string;
		message?: string;
		confirmLabel?: string;
		cancelLabel?: string;
		danger?: boolean;
		onconfirm: () => void;
		oncancel: () => void;
	} = $props();

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') oncancel();
	}

	function onOverlayClick(e: MouseEvent) {
		if (e.target === e.currentTarget) oncancel();
	}
</script>

{#if open}
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<!-- svelte-ignore a11y_interactive_supports_focus -->
	<div class="overlay" onclick={onOverlayClick} onkeydown={onKeydown} role="dialog" aria-modal="true">
		<div class="dialog">
			<h3>{title}</h3>
			<p>{message}</p>
			<div class="actions">
				<button class="cancel-btn" onclick={oncancel}>{cancelLabel}</button>
				<button class="confirm-btn" class:danger onclick={onconfirm}>{confirmLabel}</button>
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

	.dialog {
		background: var(--bg-surface, #2b2d31);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 1.25rem;
		min-width: 320px;
		max-width: 440px;
	}

	h3 {
		margin: 0 0 0.5rem;
		font-size: 1rem;
	}

	p {
		margin: 0 0 1rem;
		color: var(--text-muted);
		font-size: 0.875rem;
		line-height: 1.4;
	}

	.actions {
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
	}

	.cancel-btn,
	.confirm-btn {
		padding: 0.4rem 1rem;
		border-radius: 4px;
		border: none;
		font-size: 0.8rem;
		cursor: pointer;
	}

	.cancel-btn {
		background: none;
		color: var(--text-muted);
	}

	.cancel-btn:hover {
		color: var(--text);
	}

	.confirm-btn {
		background: var(--accent, #5865f2);
		color: white;
	}

	.confirm-btn.danger {
		background: var(--danger, #e74c3c);
	}

	.confirm-btn:hover {
		opacity: 0.9;
	}
</style>
