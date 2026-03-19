<script lang="ts">
	import { hasServerPermission, arePermissionsLoaded } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import {
		getBackupEstimate,
		listBackups,
		createBackup,
		deleteBackup,
		downloadBackupUrl,
		type BackupListEntry,
		type SizeEstimate
	} from '$lib/api/backups.js';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';
	import '$lib/styles/settings.css';

	const loaded = $derived(arePermissionsLoaded());
	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));

	let estimate = $state<SizeEstimate | null>(null);
	let backups = $state<BackupListEntry[]>([]);
	let loading = $state(false);
	let creating = $state(false);
	let error = $state('');
	let success = $state('');

	// Area checkboxes
	let includeDatabase = $state(true);
	let includeFiles = $state(false);
	let includeEmojis = $state(false);
	let password = $state('');

	const anySelected = $derived(includeDatabase || includeFiles || includeEmojis);

	onMount(async () => {
		await Promise.all([loadEstimate(), loadBackups()]);
	});

	async function loadEstimate() {
		try {
			estimate = await getBackupEstimate();
		} catch {
			// non-critical
		}
	}

	async function loadBackups() {
		try {
			backups = await listBackups();
		} catch {
			// non-critical
		}
	}

	async function handleCreate() {
		if (!anySelected) return;
		creating = true;
		error = '';
		success = '';
		try {
			const areas: string[] = [];
			if (includeDatabase) areas.push('database');
			if (includeFiles) areas.push('files');
			if (includeEmojis) areas.push('emojis');
			await createBackup(areas, password.trim() || undefined);
			success = 'Backup created successfully';
			password = '';
			await loadBackups();
			setTimeout(() => (success = ''), 4000);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to create backup';
		} finally {
			creating = false;
		}
	}

	async function handleDelete(filename: string) {
		if (!confirm(`Delete backup ${filename}?`)) return;
		error = '';
		try {
			await deleteBackup(filename);
			backups = backups.filter((b) => b.filename !== filename);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to delete backup';
		}
	}

	function formatSize(bytes: number): string {
		if (bytes < 1024) return bytes + ' B';
		if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
		if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
		return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB';
	}

	function formatDate(iso: string): string {
		return new Date(iso).toLocaleString();
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Backups</h1>
	</div>

	{#if error}
		<div class="error-message">{error}</div>
	{/if}
	{#if success}
		<div class="success-message">{success}</div>
	{/if}

	{#if !loaded}
		<p class="muted">Loading…</p>
	{:else if !canManageServer}
		<p class="muted">You don't have permission to manage backups.</p>
	{:else}
		<div class="info-banner">
			Backups can only be restored on a fresh server (before initial setup).
		</div>

		<div class="form-section">
			<div class="form-group">
				<label>Backup Areas</label>
				<div class="area-checkboxes">
					<label class="checkbox-label">
						<input type="checkbox" bind:checked={includeDatabase} />
						<span>Database</span>
						{#if estimate}
							<span class="size-hint">{formatSize(estimate.database)}</span>
						{/if}
					</label>
					<label class="checkbox-label">
						<input type="checkbox" bind:checked={includeFiles} />
						<span>Files</span>
						{#if estimate}
							<span class="size-hint">{formatSize(estimate.files)}</span>
						{/if}
					</label>
					<label class="checkbox-label">
						<input type="checkbox" checked={includeFiles || includeEmojis} disabled={includeFiles} onchange={(e) => { if (!includeFiles) includeEmojis = e.currentTarget.checked; }} />
						<span>Emojis {includeFiles ? '(included in Files)' : ''}</span>
						{#if estimate && !includeFiles}
							<span class="size-hint">{formatSize(estimate.emojis)}</span>
						{/if}
					</label>
				</div>
			</div>

			<div class="form-group">
				<label for="backupPassword">Password (optional)</label>
				<input
					type="password"
					id="backupPassword"
					bind:value={password}
					placeholder="Encrypt database backup"
					disabled={!includeDatabase}
				/>
				<span class="muted" style="font-size: 0.75rem;">
					Password encrypts the database only. Media files are not encrypted.
				</span>
			</div>

			<div class="form-actions">
				<button class="btn-primary" onclick={handleCreate} disabled={creating || !anySelected}>
					{creating ? 'Creating…' : 'Create Backup'}
				</button>
			</div>
		</div>
	{/if}
</div>

{#if loaded && canManageServer && backups.length > 0}
	<div class="settings-card">
		<div class="header-row">
			<h1>Backup History</h1>
		</div>

		<div class="backup-list">
			{#each backups as backup}
				<div class="backup-row">
					<div class="backup-info">
						<span class="backup-name">{backup.filename}</span>
						<span class="backup-meta">
							{formatSize(backup.size_bytes)} · {formatDate(backup.created_at)}
						</span>
					</div>
					<div class="backup-actions">
						<a class="btn-sm" href={downloadBackupUrl(backup.filename)} download>
							Download
						</a>
						<button class="btn-sm btn-danger" onclick={() => handleDelete(backup.filename)}>
							Delete
						</button>
					</div>
				</div>
			{/each}
		</div>
	</div>
{/if}

<style>
	.info-banner {
		background: rgba(88, 101, 242, 0.1);
		border: 1px solid rgba(88, 101, 242, 0.3);
		border-radius: 6px;
		padding: 0.6rem 0.75rem;
		font-size: 0.8rem;
		color: var(--text-muted);
		margin-bottom: 1rem;
	}

	.area-checkboxes {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	.checkbox-label {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-size: 0.875rem;
		color: var(--text);
		cursor: pointer;
	}

	.checkbox-label input[type='checkbox'] {
		accent-color: var(--accent, #5865f2);
		width: auto;
		flex-shrink: 0;
	}

	.size-hint {
		font-size: 0.75rem;
		color: var(--text-muted);
		margin-left: auto;
	}

	.backup-list {
		display: flex;
		flex-direction: column;
		gap: 0.5rem;
	}

	.backup-row {
		display: flex;
		justify-content: space-between;
		align-items: center;
		padding: 0.6rem 0.75rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 6px;
		gap: 0.75rem;
	}

	.backup-info {
		display: flex;
		flex-direction: column;
		gap: 0.15rem;
		min-width: 0;
	}

	.backup-name {
		font-size: 0.8rem;
		font-weight: 500;
		color: var(--text);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.backup-meta {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.backup-actions {
		display: flex;
		gap: 0.35rem;
		flex-shrink: 0;
	}

	.backup-actions a {
		text-decoration: none;
	}
</style>
