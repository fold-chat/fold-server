<script lang="ts">
	import { createChannel as createChannelApi, updateChannel as updateChannelApi } from '$lib/api/channels.js';
	import type { Channel } from '$lib/api/channels.js';
	import { getChannelPermissions, updateChannelPermission, deleteChannelPermission, type ChannelPermissionOverride } from '$lib/api/roles.js';
	import { getRolesList } from '$lib/stores/roles.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName, PERMISSION_GROUPS, isServerLevelPermission } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';

	let {
		open = false,
		categoryId = null,
		channel = null,
		onsave,
		oncancel
	}: {
		open: boolean;
		categoryId?: string | null;
		channel?: Channel | null;
		onsave: (channel: Channel) => void;
		oncancel: () => void;
	} = $props();

	let isEdit = $derived(!!channel);

	let channelType = $state('TEXT');
	let name = $state('');
	let topic = $state('');
	let description = $state('');
	let submitting = $state(false);
	let error = $state('');

	// Tab state (General / Permissions) — only relevant in edit mode
	let activeTab = $state<'general' | 'permissions'>('general');

	// --- Permissions state ---
	const canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));
	let overrides = $state<ChannelPermissionOverride[]>([]);
	let permLoading = $state(false);
	let permSaving = $state(false);
	let permError = $state('');
	let selectedRoleId = $state<string | null>(null);

	const THREAD_PERMS = new Set([
		PermissionName.CREATE_THREADS,
		PermissionName.MANAGE_OWN_THREADS,
		PermissionName.MANAGE_THREADS,
		PermissionName.SEND_IN_LOCKED_THREADS
	]);

	const channelPerms = $derived.by(() => {
		let groups = [...PERMISSION_GROUPS.Channel];
		if (channelType === 'VOICE') groups = [...groups, ...PERMISSION_GROUPS.Voice];
		if (channelType === 'TEXT') groups = groups.filter((p) => !THREAD_PERMS.has(p.name));
		return groups.filter((p) => !isServerLevelPermission(p.name));
	});

	// Populate fields when channel prop changes (edit mode)
	let lastChannelId = $state<string | null>(null);
	$effect(() => {
		if (open && channel && channel.id !== lastChannelId) {
			lastChannelId = channel.id;
			channelType = channel.type;
			name = channel.name;
			topic = channel.topic ?? '';
			description = channel.description ?? '';
			activeTab = 'general';
			selectedRoleId = null;
			loadOverrides(channel.id);
		} else if (open && !channel && lastChannelId !== null) {
			lastChannelId = null;
			reset();
		}
	});

	function reset() {
		channelType = 'TEXT';
		name = '';
		topic = '';
		description = '';
		submitting = false;
		error = '';
		activeTab = 'general';
		overrides = [];
		selectedRoleId = null;
		permError = '';
	}

	// --- Permissions helpers ---
	async function loadOverrides(channelId: string, silent = false) {
		if (!silent) permLoading = true;
		permError = '';
		try {
			overrides = await getChannelPermissions(channelId);
		} catch {
			permError = 'Failed to load permissions';
		} finally {
			if (!silent) permLoading = false;
		}
	}

	function getOverrideForRole(roleId: string): ChannelPermissionOverride | undefined {
		return overrides.find((o) => o.role_id === roleId);
	}

	type TriState = 'allow' | 'deny' | 'inherit';

	function getPermState(roleId: string, perm: string): TriState {
		const o = getOverrideForRole(roleId);
		if (!o) return 'inherit';
		if (o.allow.includes(perm)) return 'allow';
		if (o.deny.includes(perm)) return 'deny';
		return 'inherit';
	}

	function cycleState(current: TriState): TriState {
		if (current === 'inherit') return 'allow';
		if (current === 'allow') return 'deny';
		return 'inherit';
	}

	async function togglePerm(roleId: string, perm: string) {
		if (!canManageChannels || !channel) return;
		const current = getPermState(roleId, perm);
		const next = cycleState(current);

		const existing = getOverrideForRole(roleId);
		let allow = existing ? [...existing.allow] : [];
		let deny = existing ? [...existing.deny] : [];

		allow = allow.filter((p) => p !== perm);
		deny = deny.filter((p) => p !== perm);

		if (next === 'allow') allow.push(perm);
		else if (next === 'deny') deny.push(perm);

		if (allow.length === 0 && deny.length === 0 && existing) {
			permSaving = true;
			try {
				await deleteChannelPermission(channel.id, roleId);
				await loadOverrides(channel.id, true);
			} catch (err) {
				permError = (err as ApiError).message || 'Failed to save';
			} finally {
				permSaving = false;
			}
			return;
		}

		permSaving = true;
		try {
			await updateChannelPermission(channel.id, roleId, { allow, deny });
			await loadOverrides(channel.id, true);
		} catch (err) {
			permError = (err as ApiError).message || 'Failed to save';
		} finally {
			permSaving = false;
		}
	}

	async function handleSubmit() {
		const trimmedName = name.trim();
		if (!trimmedName) {
			error = 'Channel name is required';
			return;
		}
		submitting = true;
		error = '';
		try {
			let ch: Channel;
			if (isEdit && channel) {
				ch = await updateChannelApi(channel.id, {
					name: trimmedName,
					topic: topic.trim() || undefined,
					description: description.trim() || undefined
				});
			} else {
				ch = await createChannelApi({
					name: trimmedName,
					type: channelType,
					category_id: categoryId,
					topic: topic.trim() || undefined,
					description: description.trim() || undefined
				});
			}
			reset();
			onsave(ch);
		} catch (e: any) {
			error = e?.message || (isEdit ? 'Failed to update channel' : 'Failed to create channel');
		} finally {
			submitting = false;
		}
	}

	function handleCancel() {
		reset();
		lastChannelId = null;
		oncancel();
	}

	function onKeydown(e: KeyboardEvent) {
		if (e.key === 'Escape') handleCancel();
	}

	function onOverlayClick(e: MouseEvent) {
		if (e.target === e.currentTarget) handleCancel();
	}
</script>

{#if open}
	<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
	<!-- svelte-ignore a11y_interactive_supports_focus -->
	<div class="overlay" onclick={onOverlayClick} onkeydown={onKeydown} role="dialog" aria-modal="true">
		<div class="dialog" class:wide={isEdit}>
			<h3>{isEdit ? 'Edit Channel' : 'Create Channel'}</h3>

			{#if isEdit}
				<div class="tab-bar">
					<button class="tab" class:active={activeTab === 'general'} onclick={() => activeTab = 'general'}>General</button>
					<button class="tab" class:active={activeTab === 'permissions'} onclick={() => activeTab = 'permissions'}>Permissions</button>
				</div>
			{/if}

			{#if activeTab === 'general'}
				{#if isEdit}
					<div class="field">
						<label class="field-label">Channel Type</label>
						<div class="type-display">
							<span class="type-icon">{channelType === 'THREAD_CHANNEL' ? '💬' : channelType === 'VOICE' ? '🔊' : '#'}</span>
							<span class="type-name">{channelType === 'THREAD_CHANNEL' ? 'Threads' : channelType === 'VOICE' ? 'Voice' : 'Text'}</span>
						</div>
					</div>
				{:else}
					<div class="field">
						<label class="field-label">Channel Type</label>
						<div class="type-options">
							<button
								class="type-btn"
								class:selected={channelType === 'TEXT'}
								onclick={() => channelType = 'TEXT'}
							>
								<span class="type-icon">#</span>
								<span class="type-info">
									<span class="type-name">Text</span>
									<span class="type-desc">Send messages, images, and files</span>
								</span>
							</button>
							<button
								class="type-btn"
								class:selected={channelType === 'THREAD_CHANNEL'}
								onclick={() => channelType = 'THREAD_CHANNEL'}
							>
								<span class="type-icon">💬</span>
								<span class="type-info">
									<span class="type-name">Threads</span>
									<span class="type-desc">Threaded discussions</span>
								</span>
							</button>
							<button
								class="type-btn disabled"
								disabled
							>
								<span class="type-icon">🔊</span>
								<span class="type-info">
									<span class="type-name">Voice</span>
									<span class="type-desc">Coming soon</span>
								</span>
							</button>
						</div>
					</div>
				{/if}

				<div class="field">
					<label class="field-label" for="channel-name">Name</label>
					<!-- svelte-ignore a11y_autofocus -->
					<input
						id="channel-name"
						type="text"
						class="field-input"
						placeholder="new-channel"
						bind:value={name}
						autofocus
						onkeydown={(e) => { if (e.key === 'Enter') handleSubmit(); }}
					/>
				</div>

				<div class="field">
					<label class="field-label" for="channel-topic">Topic</label>
					<input
						id="channel-topic"
						type="text"
						class="field-input"
						placeholder="What's this channel about?"
						bind:value={topic}
					/>
				</div>

				<div class="field">
					<label class="field-label" for="channel-description">Description</label>
					<textarea
						id="channel-description"
						class="field-input field-textarea"
						placeholder="Describe the purpose of this channel..."
						rows="3"
						bind:value={description}
					></textarea>
				</div>

				{#if error}
					<p class="error">{error}</p>
				{/if}

				<div class="actions">
					<button class="cancel-btn" onclick={handleCancel}>Cancel</button>
					<button class="create-btn" onclick={handleSubmit} disabled={submitting || !name.trim()}>
						{submitting ? (isEdit ? 'Saving...' : 'Creating...') : (isEdit ? 'Save Changes' : 'Create Channel')}
					</button>
				</div>
			{:else}
				<!-- Permissions tab -->
				{#if permError}
					<div class="perm-error">{permError}</div>
				{/if}

				{#if !canManageChannels}
					<p class="muted">You don't have permission to manage channel permissions.</p>
				{/if}

				{#if permLoading}
					<p class="muted">Loading...</p>
				{:else}
					<div class="role-tabs">
						{#each getRolesList() as role}
							{#if role.id !== 'owner'}
								<button
									class="role-tab"
									class:active={selectedRoleId === role.id}
									onclick={() => (selectedRoleId = role.id)}
								>
									<span class="role-dot" style="background: {role.color || '#99aab5'}"></span>
									{role.name}
								</button>
							{/if}
						{/each}
					</div>

					{#if selectedRoleId}
						<div class="perm-matrix">
							{#each channelPerms as perm}
								{@const state = getPermState(selectedRoleId, perm.name)}
								<div class="perm-row">
									<div class="perm-info">
										<span class="perm-label">{perm.label}</span>
										<span class="perm-desc">{perm.desc}</span>
									</div>
									<button
										class="tri-toggle {state}"
										onclick={() => selectedRoleId && togglePerm(selectedRoleId, perm.name)}
										disabled={!canManageChannels || permSaving}
										title="{state === 'inherit' ? 'Inherit' : state === 'allow' ? 'Allow' : 'Deny'} — click to cycle"
									>
										{#if state === 'allow'}
											✓ Allow
										{:else if state === 'deny'}
											✕ Deny
										{:else}
											— Inherit
										{/if}
									</button>
								</div>
							{/each}
						</div>
					{:else}
						<p class="muted">Select a role to configure channel permission overrides.</p>
					{/if}
				{/if}

				<div class="actions">
					<button class="cancel-btn" onclick={handleCancel}>Close</button>
				</div>
			{/if}
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
		min-width: 400px;
		max-width: 480px;
		width: 100%;
		max-height: 80vh;
		overflow-y: auto;
	}

	.dialog.wide {
		max-width: 560px;
	}

	h3 {
		margin: 0 0 1rem;
		font-size: 1.1rem;
	}

	.field {
		margin-bottom: 0.75rem;
	}

	.field-label {
		display: block;
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
		margin-bottom: 0.35rem;
	}

	.field-input {
		width: 100%;
		background: var(--bg, #1e1f22);
		color: var(--text);
		border: 1px solid var(--border);
		border-radius: 4px;
		padding: 0.5rem 0.6rem;
		font-size: 0.875rem;
		outline: none;
		box-sizing: border-box;
	}

	.field-input:focus {
		border-color: var(--accent, #5865f2);
	}

	.field-input::placeholder {
		color: var(--text-muted);
		opacity: 0.5;
	}

	.field-textarea {
		resize: vertical;
		font-family: inherit;
		min-height: 60px;
	}

	/* Type selector */
	.type-options {
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
	}

	.type-btn {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		width: 100%;
		padding: 0.55rem 0.7rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		background: var(--bg, #1e1f22);
		color: var(--text);
		cursor: pointer;
		text-align: left;
	}

	.type-btn:hover:not(.disabled) {
		border-color: var(--text-muted);
	}

	.type-btn.selected {
		border-color: var(--accent, #5865f2);
		background: rgba(88, 101, 242, 0.1);
	}

	.type-btn.disabled {
		opacity: 0.4;
		cursor: not-allowed;
	}

	.type-display {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.55rem 0.7rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		background: var(--bg, #1e1f22);
		opacity: 0.6;
	}

	.type-icon {
		font-size: 1.1rem;
		width: 1.5rem;
		text-align: center;
		flex-shrink: 0;
	}

	.type-info {
		display: flex;
		flex-direction: column;
	}

	.type-name {
		font-size: 0.85rem;
		font-weight: 500;
	}

	.type-desc {
		font-size: 0.7rem;
		color: var(--text-muted);
	}

	/* Error */
	.error {
		color: var(--danger, #e74c3c);
		font-size: 0.8rem;
		margin: 0 0 0.5rem;
	}

	/* Actions */
	.actions {
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
		margin-top: 0.75rem;
	}

	.cancel-btn,
	.create-btn {
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

	.create-btn {
		background: var(--accent, #5865f2);
		color: white;
	}

	.create-btn:hover:not(:disabled) {
		opacity: 0.9;
	}

	.create-btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	/* Tabs */
	.tab-bar {
		display: flex;
		gap: 0;
		border-bottom: 1px solid var(--border);
		margin-bottom: 0.75rem;
	}

	.tab {
		padding: 0.4rem 0.75rem;
		background: none;
		border: none;
		border-bottom: 2px solid transparent;
		color: var(--text-muted);
		font-size: 0.8rem;
		cursor: pointer;
	}

	.tab:hover {
		color: var(--text);
	}

	.tab.active {
		color: var(--text);
		border-bottom-color: var(--accent, #5865f2);
	}

	/* Permissions tab styles */
	.muted {
		color: var(--text-muted);
		font-size: 0.8rem;
	}

	.perm-error {
		color: #e74c3c;
		font-size: 0.8rem;
		margin-bottom: 0.5rem;
		padding: 0.4rem;
		background: rgba(231, 76, 60, 0.1);
		border-radius: 4px;
	}

	.role-tabs {
		display: flex;
		gap: 0.3rem;
		flex-wrap: wrap;
		margin-bottom: 0.75rem;
	}

	.role-tab {
		display: inline-flex;
		align-items: center;
		gap: 0.3rem;
		padding: 0.3rem 0.5rem;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		border-radius: 4px;
		font-size: 0.75rem;
		cursor: pointer;
	}

	.role-tab:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	.role-tab.active {
		background: var(--bg-active, rgba(255, 255, 255, 0.08));
		color: var(--text);
		border-color: var(--accent, #5865f2);
	}

	.role-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
	}

	.perm-matrix {
		display: flex;
		flex-direction: column;
		gap: 0.2rem;
	}

	.perm-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.35rem 0.4rem;
		border-radius: 4px;
	}

	.perm-row:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.02));
	}

	.perm-info {
		display: flex;
		flex-direction: column;
	}

	.perm-label {
		font-size: 0.8rem;
		font-weight: 500;
	}

	.perm-desc {
		font-size: 0.65rem;
		color: var(--text-muted);
	}

	.tri-toggle {
		padding: 0.2rem 0.5rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		font-size: 0.7rem;
		cursor: pointer;
		min-width: 75px;
		text-align: center;
		background: none;
		color: var(--text-muted);
	}

	.tri-toggle.allow {
		background: rgba(46, 204, 113, 0.15);
		border-color: #2ecc71;
		color: #2ecc71;
	}

	.tri-toggle.deny {
		background: rgba(231, 76, 60, 0.15);
		border-color: #e74c3c;
		color: #e74c3c;
	}

	.tri-toggle.inherit {
		background: none;
		color: var(--text-muted);
	}

	.tri-toggle:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}
</style>
