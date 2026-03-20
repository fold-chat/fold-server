<script lang="ts">
	import { onMount } from 'svelte';

	let serverName = $state('Fold');
	let iconUrl = $state<string | null>(null);

	onMount(async () => {
		try {
			const res = await fetch('/api/v0/status');
			if (res.ok) {
				const data = await res.json();
				if (data.server_name) serverName = data.server_name;
				if (data.server_icon_url) iconUrl = data.server_icon_url;
			}
		} catch { /* server unreachable */ }
	});
</script>

<div class="server-branding">
	{#if iconUrl}
		<img src={iconUrl} alt="" class="branding-icon" />
	{/if}
	<span class="branding-name">{serverName}</span>
</div>

<style>
	.server-branding {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.5rem;
		margin-bottom: 1.25rem;
	}

	.branding-icon {
		width: 64px;
		height: 64px;
		border-radius: 14px;
		object-fit: cover;
	}

	.branding-name {
		font-size: 1.1rem;
		font-weight: 700;
		color: var(--text);
	}
</style>
