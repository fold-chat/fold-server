<script lang="ts">
	import { getInviteInfo, type InviteInfo } from '$lib/api/invites.js';
	import { page } from '$app/state';
	import { onMount } from 'svelte';

	let invite = $state<InviteInfo | null>(null);
	let error = $state('');
	let loading = $state(true);

	const code = page.params.code ?? '';

	onMount(async () => {
		try {
			invite = await getInviteInfo(code);
		} catch {
			error = 'Invite not found';
		} finally {
			loading = false;
		}
	});
</script>

<div class="auth-page">
	<div class="auth-card" style="text-align: center">
		{#if loading}
			<p>Loading invite...</p>
		{:else if error}
			<h1>Invalid invite</h1>
			<p>{error}</p>
	{:else if invite && invite.valid}
		{#if invite.server_icon}
			<img src={invite.server_icon} alt="" class="server-icon" />
		{/if}
		<h1>You've been invited!</h1>
		<p>Join <strong>{invite.server_name}</strong></p>
		<a href="/register?invite={code}">
			<button style="width: 100%">Accept invite</button>
		</a>
		{:else}
			<h1>Invite expired</h1>
			<p>This invite is no longer valid.</p>
		{/if}
	</div>
<div class="powered-by">Powered by <a href="https://fold.chat" target="_blank" rel="noopener">fold.chat</a></div>
</div>

<style>
	.server-icon {
		width: 80px;
		height: 80px;
		border-radius: 16px;
		object-fit: cover;
		margin: 0 auto 0.75rem;
		display: block;
	}
</style>
