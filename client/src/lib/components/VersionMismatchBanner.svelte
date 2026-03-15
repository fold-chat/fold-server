<script lang="ts">
	import { isVersionMismatch, isMismatchDismissed, dismissMismatch, getClientVersion, getServerVersion } from '$lib/stores/version.svelte.js';
</script>

{#if isVersionMismatch() && !isMismatchDismissed()}
	<div class="version-mismatch-banner">
		<span>Client and server versions don't match (client: {getClientVersion()}, server: {getServerVersion()}). You may need to refresh.</span>
		<button class="dismiss-btn" onclick={dismissMismatch}>&times;</button>
	</div>
{/if}

<style>
	.version-mismatch-banner {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0.75rem;
		background: var(--warning-bg, #92400e);
		color: var(--warning-text, #fef3c7);
		font-size: 0.8rem;
		padding: 0.4rem 1rem;
		flex-shrink: 0;
	}

	.dismiss-btn {
		background: none;
		border: none;
		color: inherit;
		font-size: 1.1rem;
		cursor: pointer;
		padding: 0 0.25rem;
		line-height: 1;
		opacity: 0.7;
	}

	.dismiss-btn:hover {
		opacity: 1;
	}
</style>
