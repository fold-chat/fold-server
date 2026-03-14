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
			<h1>You've been invited!</h1>
			<p>Join the Fold community</p>
			<a href="/register?invite={code}">
				<button style="width: 100%">Accept invite</button>
			</a>
		{:else}
			<h1>Invite expired</h1>
			<p>This invite is no longer valid.</p>
		{/if}
	</div>
</div>
