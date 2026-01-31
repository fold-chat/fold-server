<script lang="ts">
	import { getAuditLog, type AuditLogEntry } from '$lib/api/audit.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';

	let entries = $state<AuditLogEntry[]>([]);
	let loading = $state(true);
	let loadingMore = $state(false);
	let error = $state('');
	let hasMore = $state(true);
	let scrollContainer: HTMLDivElement;

	const canViewAudit = $derived(
		hasServerPermission(PermissionName.ADMINISTRATOR) || hasServerPermission(PermissionName.MANAGE_SERVER)
	);

	onMount(() => {
		if (!canViewAudit) {
			error = 'Insufficient permissions';
			loading = false;
			return;
		}
		loadEntries();
	});

	async function loadEntries() {
		try {
			const result = await getAuditLog({ limit: 50 });
			entries = result.entries;
			hasMore = result.entries.length === 50;
		} catch (err) {
			error = (err as ApiError).message || 'Failed to load audit log';
		} finally {
			loading = false;
		}
	}

	async function loadMore() {
		if (!hasMore || loadingMore) return;
		loadingMore = true;
		try {
			const lastEntry = entries[entries.length - 1];
			const result = await getAuditLog({ limit: 50, before: lastEntry.id });
			entries = [...entries, ...result.entries];
			hasMore = result.entries.length === 50;
		} catch (err) {
			error = (err as ApiError).message || 'Failed to load more';
		} finally {
			loadingMore = false;
		}
	}

	function handleScroll(e: Event) {
		const target = e.target as HTMLDivElement;
		const bottom = target.scrollHeight - target.scrollTop <= target.clientHeight + 200;
		if (bottom && !loadingMore && hasMore) {
			loadMore();
		}
	}

	function formatDate(dateStr: string): string {
		const date = new Date(dateStr + 'Z');
		const now = new Date();
		const diffMs = now.getTime() - date.getTime();
		const diffMins = Math.floor(diffMs / 60000);
		const diffHours = Math.floor(diffMs / 3600000);
		const diffDays = Math.floor(diffMs / 86400000);

		if (diffMins < 1) return 'just now';
		if (diffMins < 60) return `${diffMins}m ago`;
		if (diffHours < 24) return `${diffHours}h ago`;
		if (diffDays < 7) return `${diffDays}d ago`;
		return date.toLocaleDateString();
	}

	function actionIcon(action: string): string {
		if (action.includes('BAN')) return '🔨';
		if (action.includes('UNBAN')) return '✓';
		if (action.includes('CREATE')) return '+';
		if (action.includes('DELETE') || action.includes('REVOKE')) return '×';
		if (action.includes('UPDATE') || action.includes('ASSIGN') || action.includes('REMOVE')) return '✎';
		return '•';
	}

	function actionLabel(action: string): string {
		return action.replace(/_/g, ' ').toLowerCase();
	}

	function parseDetails(entry: AuditLogEntry): string | null {
		if (!entry.details) return null;
		try {
			const parsed = JSON.parse(entry.details);
			const parts: string[] = [];
			if (parsed.reason) parts.push(`Reason: ${parsed.reason}`);
			if (parsed.name) parts.push(`Name: ${parsed.name}`);
			if (parsed.role_id) parts.push(`Role: ${parsed.role_id}`);
			if (parsed.code) parts.push(`Code: ${parsed.code}`);
			return parts.join(', ');
		} catch {
			return null;
		}
	}
</script>

<div class="settings-card" bind:this={scrollContainer} onscroll={handleScroll}>
	<div class="header-row">
		<h1>Audit Log</h1>
	</div>

		{#if error}
			<div class="error-message">{error}</div>
		{/if}

		{#if loading}
			<p class="muted">Loading...</p>
		{:else}
			<div class="log-list">
				{#each entries as entry}
					<div class="log-entry">
						<span class="log-icon">{actionIcon(entry.action)}</span>
						<div class="log-content">
							<div class="log-main">
								<span class="log-actor">{entry.actor_username || 'System'}</span>
								<span class="log-action">{actionLabel(entry.action)}</span>
								{#if entry.target_type && entry.target_id}
									<span class="log-target">{entry.target_type} {entry.target_id}</span>
								{/if}
							</div>
							{#if parseDetails(entry)}
								<div class="log-details">{parseDetails(entry)}</div>
							{/if}
						</div>
						<span class="log-timestamp">{formatDate(entry.created_at)}</span>
					</div>
				{/each}

				{#if loadingMore}
					<p class="muted load-more">Loading more...</p>
				{/if}

				{#if !hasMore && entries.length > 0}
					<p class="muted load-more">End of log</p>
				{/if}

				{#if entries.length === 0}
					<p class="muted">No audit log entries yet</p>
				{/if}
			</div>
		{/if}
</div>

<style>
	.log-list {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	.log-entry {
		display: flex;
		align-items: flex-start;
		gap: 0.75rem;
		padding: 0.75rem;
		border-radius: 4px;
		background: var(--bg, rgba(0, 0, 0, 0.2));
		border: 1px solid var(--border, rgba(255, 255, 255, 0.05));
	}

	.log-icon {
		font-size: 1rem;
		flex-shrink: 0;
		opacity: 0.7;
	}

	.log-content {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 0.25rem;
		min-width: 0;
	}

	.log-main {
		display: flex;
		align-items: baseline;
		gap: 0.5rem;
		flex-wrap: wrap;
	}

	.log-actor {
		font-weight: 600;
		font-size: 0.875rem;
		color: var(--text);
	}

	.log-action {
		font-size: 0.875rem;
		color: var(--text-muted);
		text-transform: capitalize;
	}

	.log-target {
		font-size: 0.8rem;
		color: var(--text-muted);
		font-family: monospace;
	}

	.log-details {
		font-size: 0.75rem;
		color: var(--text-muted);
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.log-timestamp {
		font-size: 0.75rem;
		color: var(--text-muted);
		flex-shrink: 0;
	}

	.load-more {
		text-align: center;
		padding: 1rem 0;
	}

</style>
