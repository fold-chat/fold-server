<script lang="ts">
	import { listBots, createBot, updateBot, uploadBotAvatar, regenerateToken, enableBot, disableBot, deleteBot, type Bot, type CreateBotResult } from '$lib/api/bots.js';
	import AvatarCropper from '$lib/components/AvatarCropper.svelte';
	import type { ApiError } from '$lib/api/client.js';
	import { onMount } from 'svelte';
	import { assignRole, removeRole } from '$lib/api/roles.js';
	import { getRolesList } from '$lib/stores/roles.svelte.js';

	let bots = $state<Bot[]>([]);
	let loading = $state(true);
	let error = $state('');
	let success = $state('');

	// Create dialog
	let showCreate = $state(false);
	let createUsername = $state('');
	let createDisplayName = $state('');
	let creating = $state(false);
	let createdToken = $state<{ token: string; bot: CreateBotResult } | null>(null);
	let tokenCopied = $state(false);

	// Edit panel
	let editingBotId = $state<string | null>(null);
	let editDisplayName = $state('');
	let editSaving = $state(false);
	let editError = $state('');
	let avatarCropFile = $state<File | null>(null);
	let avatarUploading = $state(false);
	let pendingRoleIds = $state(new Set<string>());
	let originalRoleIds = $state(new Set<string>());

	function parseBotRoles(raw: unknown): import('$lib/api/users.js').RoleBadge[] {
		if (Array.isArray(raw)) return raw.filter(r => r?.id != null);
		if (typeof raw === 'string') {
			try {
				const parsed = JSON.parse(raw);
				return Array.isArray(parsed) ? parsed.filter((r: { id: unknown }) => r?.id != null) : [];
			} catch { return []; }
		}
		return [];
	}

	// Regenerate token
	let regenBotId = $state<string | null>(null);
	let regenResult = $state<string | null>(null);
	let regenCopied = $state(false);

	// Disable confirmation
	let disableConfirmBot = $state<Bot | null>(null);

	// Delete confirmation
	let deleteConfirmBot = $state<Bot | null>(null);
	let deleteConfirmName = $state('');

	onMount(async () => {
		await load();
	});

	async function load() {
		loading = true;
		error = '';
		try {
			bots = await listBots();
		} catch {
			error = 'Failed to load bots';
		} finally {
			loading = false;
		}
	}

	// --- Create ---

	async function handleCreate() {
		if (!createUsername.trim()) return;
		creating = true;
		error = '';
		try {
		const result = await createBot({
				username: createUsername.trim(),
				display_name: createDisplayName.trim() || undefined
			});
			createdToken = { token: result.token, bot: result };
			createUsername = '';
			createDisplayName = '';
			showCreate = false;
			bots = await listBots();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to create bot';
		} finally {
			creating = false;
		}
	}

	function copyCreatedToken() {
		if (createdToken) {
			navigator.clipboard.writeText(createdToken.token).then(() => {
				tokenCopied = true;
				setTimeout(() => { tokenCopied = false; }, 2000);
			});
		}
	}

	// --- Edit ---

	function openEdit(bot: Bot) {
		editingBotId = bot.id;
		editDisplayName = bot.display_name ?? '';
		editError = '';
		const ids = new Set(parseBotRoles(bot.roles).map(r => r.id));
		originalRoleIds = ids;
		pendingRoleIds = new Set(ids);
	}

	function closeEdit() {
		editingBotId = null;
		editError = '';
	}

	function togglePendingRole(roleId: string) {
		const next = new Set(pendingRoleIds);
		if (next.has(roleId)) next.delete(roleId); else next.add(roleId);
		pendingRoleIds = next;
	}

	async function handleSaveBotEdit() {
		if (!editingBotId) return;
		editSaving = true;
		editError = '';
		try {
			await updateBot(editingBotId, { display_name: editDisplayName });
			for (const id of pendingRoleIds) {
				if (!originalRoleIds.has(id)) await assignRole(editingBotId, id);
			}
			for (const id of originalRoleIds) {
				if (!pendingRoleIds.has(id)) await removeRole(editingBotId, id);
			}
			bots = await listBots();
			const updated = bots.find(b => b.id === editingBotId);
			if (updated) {
				const ids = new Set(parseBotRoles(updated.roles).map(r => r.id));
				originalRoleIds = ids;
				pendingRoleIds = new Set(ids);
			}
			success = 'Bot updated';
			setTimeout(() => { success = ''; }, 2000);
		} catch (err) {
			editError = (err as ApiError).message || 'Failed to update bot';
		} finally {
			editSaving = false;
		}
	}

	function handleAvatarSelect(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		avatarCropFile = file;
		input.value = '';
	}

	async function handleCroppedAvatar(croppedFile: File) {
		avatarCropFile = null;
		if (!editingBotId) return;
		avatarUploading = true;
		editError = '';
		try {
			const updated = await uploadBotAvatar(editingBotId, croppedFile);
			bots = bots.map(b => b.id === editingBotId ? { ...b, ...updated } : b);
			success = 'Avatar updated';
			setTimeout(() => { success = ''; }, 2000);
		} catch (err) {
			editError = (err as ApiError).message || 'Failed to upload avatar';
		} finally {
			avatarUploading = false;
		}
	}

	function handleCropCancel() {
		avatarCropFile = null;
	}

	// --- Regenerate token ---

	function openRegen(bot: Bot) {
		regenBotId = bot.id;
		regenResult = null;
		regenCopied = false;
	}

	async function handleRegen() {
		if (!regenBotId) return;
		error = '';
		try {
			const result = await regenerateToken(regenBotId);
			regenResult = result.token;
			bots = await listBots();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to regenerate token';
		}
	}

	function copyRegenToken() {
		if (regenResult) {
			navigator.clipboard.writeText(regenResult).then(() => {
				regenCopied = true;
				setTimeout(() => { regenCopied = false; }, 2000);
			});
		}
	}

	function closeRegen() {
		regenBotId = null;
		regenResult = null;
	}

	// --- Enable / Disable ---

	async function handleToggleEnabled(bot: Bot) {
		if (!bot.bot_enabled) {
			// Enable — no confirmation needed
			error = '';
			try {
				await enableBot(bot.id);
				bots = bots.map(b => b.id === bot.id ? { ...b, bot_enabled: 1 } : b);
			} catch (err) {
				error = (err as ApiError).message || 'Failed to enable bot';
			}
		} else {
			disableConfirmBot = bot;
		}
	}

	async function confirmDisable() {
		if (!disableConfirmBot) return;
		error = '';
		try {
			await disableBot(disableConfirmBot.id);
			bots = bots.map(b => b.id === disableConfirmBot!.id ? { ...b, bot_enabled: 0 } : b);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to disable bot';
		} finally {
			disableConfirmBot = null;
		}
	}

	// --- Delete ---

	async function confirmDelete() {
		if (!deleteConfirmBot) return;
		if (deleteConfirmName !== (deleteConfirmBot.display_name || deleteConfirmBot.username)) return;
		error = '';
		try {
			await deleteBot(deleteConfirmBot.id);
			bots = bots.filter(b => b.id !== deleteConfirmBot!.id);
			// Close edit panel if open for this bot
			if (editingBotId === deleteConfirmBot.id) closeEdit();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to delete bot';
		} finally {
			deleteConfirmBot = null;
			deleteConfirmName = '';
		}
	}

	function formatDate(dateStr: string | null): string {
		if (!dateStr) return 'Never';
		try {
			return new Date(dateStr.endsWith('Z') ? dateStr : dateStr + 'Z')
				.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' });
		} catch {
			return dateStr;
		}
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Bots</h1>
		<button class="btn-primary" onclick={() => { showCreate = true; error = ''; }}>+ New Bot</button>
	</div>

	{#if error}
		<div class="error-message">{error}</div>
	{/if}
	{#if success}
		<div class="success-message">{success}</div>
	{/if}

	{#if createdToken}
		<div class="token-reveal">
			<p class="token-reveal-title">Bot created! Copy your token now — it won't be shown again.</p>
			<div class="token-box">
				<code class="token-text">{createdToken.token}</code>
				<button class="btn-copy" onclick={copyCreatedToken}>{tokenCopied ? 'Copied!' : 'Copy'}</button>
			</div>
			<button class="btn-sm dismiss-btn" onclick={() => { createdToken = null; }}>Dismiss</button>
		</div>
	{/if}

	{#if loading}
		<p class="muted">Loading...</p>
	{:else if bots.length === 0}
		<p class="muted">No bots yet. Create one to get started.</p>
	{:else}
		<table class="bots-table">
			<thead>
				<tr>
					<th class="col-bot">Bot</th>
					<th class="col-status">Status</th>
					<th class="col-token">Token last used</th>
					<th class="col-actions"></th>
				</tr>
			</thead>
			<tbody>
				{#each bots as bot}
					<tr class="bot-row" class:disabled={!bot.bot_enabled}>
						<td class="col-bot">
							<div class="bot-cell">
								{#if bot.avatar_url}
									<img src={bot.avatar_url} alt="" class="bot-avatar" />
								{:else}
									<div class="bot-avatar placeholder">{(bot.display_name || bot.username).charAt(0).toUpperCase()}</div>
								{/if}
								<div class="bot-info">
									<span class="bot-name">{bot.display_name || bot.username}</span>
									<span class="bot-username">@{bot.username}</span>
								</div>
								<span class="bot-tag">BOT</span>
							</div>
						</td>
						<td class="col-status">
							{#if bot.bot_enabled}
								<span class="status-badge enabled">Enabled</span>
							{:else}
								<span class="status-badge disabled">Disabled</span>
							{/if}
						</td>
						<td class="col-token">{formatDate(bot.token_last_used_at)}</td>
						<td class="col-actions">
							<button class="edit-btn" onclick={() => editingBotId === bot.id ? closeEdit() : openEdit(bot)} title="Edit">
								<span class="material-symbols-outlined">edit</span>
							</button>
						</td>
					</tr>
					{#if editingBotId === bot.id}
						<tr class="expand-row">
							<td colspan="4">
								<div class="edit-panel">
									{#if editError}
										<div class="error-message">{editError}</div>
									{/if}

									<div class="edit-section">
										<div class="edit-section-label">Avatar</div>
										<div class="avatar-section">
											{#if bot.avatar_url}
												<img src={bot.avatar_url} alt="" class="edit-avatar" />
											{:else}
												<div class="edit-avatar placeholder">{(bot.display_name || bot.username).charAt(0).toUpperCase()}</div>
											{/if}
											<label class="upload-btn">
												{avatarUploading ? 'Uploading...' : 'Change avatar'}
												<input type="file" accept="image/*" onchange={handleAvatarSelect} hidden />
											</label>
										</div>
									</div>

									<div class="edit-section">
										<div class="edit-section-label">Display Name</div>
										<input class="name-input" type="text" bind:value={editDisplayName} placeholder="Display name" />
									</div>

									<div class="edit-section">
										<div class="edit-section-label">Roles</div>
										<div class="edit-roles">
											{#each getRolesList().filter(r => r.id !== 'owner') as role}
												<label class="role-item">
													<input type="checkbox"
														checked={pendingRoleIds.has(role.id)}
														onchange={() => togglePendingRole(role.id)}
													/>
													<span class="role-dot" style="background: {role.color || '#99aab5'}"></span>
													<span>{role.name}</span>
												</label>
											{/each}
										</div>
										<button class="btn-sm" onclick={handleSaveBotEdit} disabled={editSaving} style="margin-top: 0.5rem;">
											{editSaving ? 'Saving...' : 'Save Changes'}
										</button>
									</div>

					<div class="edit-section">
									<div class="edit-section-label">Token</div>
									<span class="token-created">Created {formatDate(bot.token_created_at)}</span>
									<button class="btn-sm" onclick={() => openRegen(bot)}>Regenerate Token</button>
								</div>

									<div class="edit-section edit-actions-row">
										<button
											class="btn-sm"
											onclick={() => handleToggleEnabled(bot)}
										>
											{bot.bot_enabled ? 'Disable Bot' : 'Enable Bot'}
										</button>
										<button class="btn-sm btn-danger" onclick={() => { deleteConfirmBot = bot; deleteConfirmName = ''; }}>
											Delete Bot
										</button>
									</div>
								</div>
							</td>
						</tr>
					{/if}
				{/each}
			</tbody>
		</table>
	{/if}
</div>

<!-- Create dialog -->
{#if showCreate}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="modal-overlay" onkeydown={(e) => e.key === 'Escape' && (showCreate = false)} onclick={() => (showCreate = false)}>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="modal" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.key === 'Escape' && (showCreate = false)}>
			<h2>Create Bot</h2>
			<div class="form-group">
				<label for="bot-username">Username <span class="required">*</span></label>
				<input id="bot-username" type="text" bind:value={createUsername} placeholder="my-bot" />
			</div>
			<div class="form-group">
				<label for="bot-display">Display Name</label>
				<input id="bot-display" type="text" bind:value={createDisplayName} placeholder="My Bot" />
			</div>
			<div class="modal-actions">
				<button class="btn-sm" onclick={() => (showCreate = false)}>Cancel</button>
				<button class="btn-sm btn-primary" onclick={handleCreate} disabled={creating || !createUsername.trim()}>
					{creating ? 'Creating...' : 'Create Bot'}
				</button>
			</div>
		</div>
	</div>
{/if}

<!-- Regenerate token dialog -->
{#if regenBotId}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="modal-overlay" onkeydown={(e) => e.key === 'Escape' && closeRegen()} onclick={closeRegen}>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="modal" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.key === 'Escape' && closeRegen()}>
		{#if regenResult}
				<h2>New Token Generated</h2>
				<p class="modal-desc">Copy your new token — the old one is permanently revoked and this won't be shown again.</p>
				<div class="token-box">
					<code class="token-text">{regenResult}</code>
					<button class="btn-copy" onclick={copyRegenToken}>{regenCopied ? 'Copied!' : 'Copy'}</button>
				</div>
				<div class="modal-actions">
					<button class="btn-sm" onclick={closeRegen}>Done</button>
				</div>
			{:else}
				<h2>Regenerate Token</h2>
				<p class="modal-desc warning">This will permanently revoke the current token. Any integrations using it will stop working immediately.</p>
				<div class="modal-actions">
					<button class="btn-sm" onclick={closeRegen}>Cancel</button>
					<button class="btn-sm btn-danger" onclick={handleRegen}>Regenerate</button>
				</div>
			{/if}
		</div>
	</div>
{/if}

<!-- Disable confirmation -->
{#if disableConfirmBot}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="modal-overlay" onkeydown={(e) => e.key === 'Escape' && (disableConfirmBot = null)} onclick={() => (disableConfirmBot = null)}>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="modal" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.key === 'Escape' && (disableConfirmBot = null)}>
			<h2>Disable Bot — {disableConfirmBot.display_name || disableConfirmBot.username}</h2>
			<p class="modal-desc">The bot's token will be rejected and it will stop responding. Its messages and content are preserved. You can re-enable it at any time.</p>
			<div class="modal-actions">
				<button class="btn-sm" onclick={() => (disableConfirmBot = null)}>Cancel</button>
				<button class="btn-sm" onclick={confirmDisable}>Disable</button>
			</div>
		</div>
	</div>
{/if}

<!-- Delete confirmation -->
{#if deleteConfirmBot}
	<!-- svelte-ignore a11y_no_static_element_interactions -->
	<div class="modal-overlay" onkeydown={(e) => e.key === 'Escape' && (deleteConfirmBot = null)} onclick={() => { deleteConfirmBot = null; deleteConfirmName = ''; }}>
		<!-- svelte-ignore a11y_no_static_element_interactions -->
		<div class="modal" onclick={(e) => e.stopPropagation()} onkeydown={(e) => e.key === 'Escape' && (deleteConfirmBot = null)}>
			<h2>Delete Bot — {deleteConfirmBot.display_name || deleteConfirmBot.username}</h2>
			<p class="modal-desc warning">This will permanently delete the bot and <strong>all content it produced</strong> (messages, reactions, etc.). This cannot be undone.</p>
			<div class="form-group">
				<label for="delete-confirm">Type <strong>{deleteConfirmBot.display_name || deleteConfirmBot.username}</strong> to confirm</label>
				<input id="delete-confirm" type="text" bind:value={deleteConfirmName} />
			</div>
			<div class="modal-actions">
				<button class="btn-sm" onclick={() => { deleteConfirmBot = null; deleteConfirmName = ''; }}>Cancel</button>
				<button
					class="btn-sm btn-danger"
					onclick={confirmDelete}
					disabled={deleteConfirmName !== (deleteConfirmBot.display_name || deleteConfirmBot.username)}
				>Delete Bot</button>
			</div>
		</div>
	</div>
{/if}

{#if avatarCropFile}
	<AvatarCropper imageFile={avatarCropFile} onCrop={handleCroppedAvatar} onCancel={handleCropCancel} />
{/if}

<style>
	.header-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		margin-bottom: 1rem;
	}

	h1 {
		margin: 0;
		font-size: 1.2rem;
	}

	/* Table */
	.bots-table {
		width: 100%;
		border-collapse: collapse;
		font-size: 0.85rem;
	}

	.bots-table thead th {
		text-align: left;
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
		padding: 0.5rem;
		border-bottom: 1px solid var(--border);
	}

	.bots-table tbody td {
		padding: 0.5rem;
		vertical-align: middle;
		border-bottom: 1px solid rgba(255, 255, 255, 0.03);
	}

	.bot-row.disabled {
		opacity: 0.55;
	}

	.col-bot { width: 40%; }
	.col-status { width: 15%; }
	.col-token { width: 25%; }
	.col-actions { width: 10%; text-align: center; }

	.bot-cell {
		display: flex;
		align-items: center;
		gap: 0.6rem;
	}

	.bot-avatar {
		width: 32px;
		height: 32px;
		border-radius: 50%;
		object-fit: cover;
		flex-shrink: 0;
	}

	.bot-avatar.placeholder {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--accent, #5865f2);
		color: white;
		font-size: 0.85rem;
		font-weight: 600;
	}

	.bot-info {
		display: flex;
		flex-direction: column;
		min-width: 0;
	}

	.bot-name {
		font-weight: 500;
		font-size: 0.85rem;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.bot-username {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	.bot-tag {
		font-size: 0.55rem;
		font-weight: 700;
		letter-spacing: 0.06em;
		background: var(--accent, #5865f2);
		color: white;
		padding: 0.05rem 0.3rem;
		border-radius: 3px;
		flex-shrink: 0;
	}

	.status-badge {
		font-size: 0.7rem;
		font-weight: 600;
		padding: 0.15rem 0.5rem;
		border-radius: 4px;
	}

	.status-badge.enabled {
		background: rgba(35, 165, 90, 0.15);
		color: #23a55a;
	}

	.status-badge.disabled {
		background: rgba(153, 153, 153, 0.15);
		color: var(--text-muted);
	}

	.edit-btn {
		background: none;
		border: 1px solid var(--border);
		border-radius: 4px;
		color: var(--text-muted);
		cursor: pointer;
		padding: 0.2rem;
		display: inline-flex;
		align-items: center;
		justify-content: center;
	}

	.edit-btn:hover {
		color: var(--text);
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
	}

	.edit-btn .material-symbols-outlined {
		font-size: 16px;
	}

	/* Edit panel */
	.expand-row td {
		padding: 0 !important;
		border-bottom: 1px solid var(--border);
	}

	.edit-panel {
		padding: 1rem;
		background: var(--bg);
		border-top: 1px solid var(--border);
		display: flex;
		flex-direction: column;
		gap: 1rem;
	}

	.edit-section {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}

	.edit-section-label {
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
	}

	.avatar-section {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.edit-avatar {
		width: 48px;
		height: 48px;
		border-radius: 50%;
		object-fit: cover;
	}

	.edit-avatar.placeholder {
		display: flex;
		align-items: center;
		justify-content: center;
		background: var(--accent, #5865f2);
		color: white;
		font-size: 1.2rem;
		font-weight: 600;
	}

	.upload-btn {
		cursor: pointer;
		color: var(--accent);
		font-size: 0.8rem;
	}

	.upload-btn:hover {
		text-decoration: underline;
	}

	.name-input {
		width: 100%;
		max-width: 280px;
	}

	.edit-roles {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
		gap: 0.4rem 1rem;
	}

	.role-item {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		font-size: 0.8rem;
		cursor: pointer;
	}

	.role-item input[type='checkbox'] {
		width: auto;
		flex-shrink: 0;
	}

	.role-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		flex-shrink: 0;
	}

	.token-created {
		color: var(--text-muted);
		font-size: 0.75rem;
	}

	.edit-actions-row {
		flex-direction: row;
		gap: 0.5rem;
	}

	/* Token reveal */
	.token-reveal {
		background: rgba(35, 165, 90, 0.08);
		border: 1px solid rgba(35, 165, 90, 0.3);
		border-radius: 6px;
		padding: 1rem;
		margin-bottom: 1rem;
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
	}

	.token-reveal-title {
		margin: 0;
		font-weight: 600;
		font-size: 0.85rem;
		color: #23a55a;
	}

	.token-box {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 4px;
		padding: 0.5rem 0.75rem;
	}

	.token-text {
		flex: 1;
		font-size: 0.75rem;
		font-family: monospace;
		word-break: break-all;
		color: var(--text);
	}

	.btn-copy {
		font-size: 0.75rem;
		padding: 0.2rem 0.5rem;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 3px;
		cursor: pointer;
		flex-shrink: 0;
	}

	.dismiss-btn {
		width: fit-content;
	}

	.muted {
		color: var(--text-muted);
		font-size: 0.875rem;
	}

	/* Modal */
	.modal-overlay {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.6);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 100;
	}

	.modal {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 8px;
		padding: 1.5rem;
		min-width: 320px;
		max-width: 440px;
		width: 100%;
	}

	.modal h2 {
		margin: 0 0 0.5rem;
		font-size: 1.1rem;
	}

	.modal-desc {
		color: var(--text-muted);
		font-size: 0.85rem;
		margin: 0 0 1rem;
	}

	.modal-desc.warning {
		color: var(--danger, #e74c3c);
	}

	.form-group {
		margin-bottom: 1rem;
	}

	.form-group label {
		display: block;
		font-size: 0.8rem;
		color: var(--text-muted);
		margin-bottom: 0.25rem;
	}

	.form-group input {
		width: 100%;
		padding: 0.5rem 0.6rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		background: var(--bg);
		color: var(--text);
		font-size: 0.875rem;
		box-sizing: border-box;
	}

	.required {
		color: var(--danger, #e74c3c);
	}

	.modal-actions {
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
		margin-top: 1rem;
	}

	.btn-primary {
		padding: 0.4rem 0.9rem;
		background: var(--accent, #5865f2);
		color: white;
		border: none;
		border-radius: 4px;
		cursor: pointer;
		font-size: 0.85rem;
		font-family: inherit;
	}

	.btn-primary:hover {
		opacity: 0.9;
	}
</style>
