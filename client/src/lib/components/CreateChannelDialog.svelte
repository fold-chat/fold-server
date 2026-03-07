<script lang="ts">
	import { createChannel as createChannelApi, updateChannel as updateChannelApi, archiveChannel as archiveChannelApi, unarchiveChannel as unarchiveChannelApi } from '$lib/api/channels.js';
	import type { Channel } from '$lib/api/channels.js';
	import { getChannelPermissions, updateChannelPermission, deleteChannelPermission, type ChannelPermissionOverride } from '$lib/api/roles.js';
	import { getRolesList } from '$lib/stores/roles.svelte.js';
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName, PERMISSION_GROUPS, isServerLevelPermission } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { isVoiceVideoEnabled } from '$lib/stores/voice.svelte.js';
	import { ICON_GROUPS, DEFAULT_ICONS } from '$lib/icons.js';
	import { uploadFile } from '$lib/api/upload.js';

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
	let isArchivable = $derived(isEdit && channel != null && channel.type !== 'VOICE');
	let isArchived = $derived(!!channel?.archived_at);
	let archiving = $state(false);

	let channelType = $state('TEXT');
	let name = $state('');
	let topic = $state('');
	let description = $state('');
	let submitting = $state(false);
	let error = $state('');

	// Icon state
	let iconMode = $state<'preset' | 'upload'>('preset');
	let selectedIcon = $state<string | null>(null);
	let customIconUrl = $state<string | null>(null);
	let uploading = $state(false);

	// Resolved icon = explicit selection or default for type
	let effectiveIcon = $derived(selectedIcon ?? DEFAULT_ICONS[channelType] ?? 'tag');

	// Tab state (General / Permissions) — only relevant in edit mode
	let activeTab = $state<'general' | 'permissions'>('general');

	// --- Permissions state ---
	const canManageChannels = $derived(hasServerPermission(PermissionName.MANAGE_CHANNELS));
	let overrides = $state<ChannelPermissionOverride[]>([]);
	let permLoading = $state(false);
	let permSaving = $state(false);
	let permError = $state('');
	let selectedRoleId = $state<string | null>(null);

	const THREAD_PERMS = new Set<string>([
		PermissionName.CREATE_THREADS,
		PermissionName.MANAGE_OWN_THREADS,
		PermissionName.MANAGE_THREADS,
		PermissionName.SEND_IN_LOCKED_THREADS
	]);

	const channelPerms = $derived.by(() => {
		let groups: ReadonlyArray<{ readonly name: string; readonly label: string; readonly desc: string }> = [...PERMISSION_GROUPS.Channel];
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
			if (channel.icon_url) {
				iconMode = 'upload';
				customIconUrl = channel.icon_url;
				selectedIcon = null;
			} else {
				iconMode = 'preset';
				selectedIcon = channel.icon;
				customIconUrl = null;
			}
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
		iconMode = 'preset';
		selectedIcon = null;
		customIconUrl = null;
		uploading = false;
		archiving = false;
		activeTab = 'general';
		overrides = [];
		selectedRoleId = null;
		permError = '';
	}

	async function handleIconUpload(e: Event) {
		const input = e.target as HTMLInputElement;
		const file = input.files?.[0];
		if (!file) return;
		uploading = true;
		error = '';
		try {
			const result = await uploadFile(file);
			customIconUrl = result.url;
			selectedIcon = null;
		} catch (err: any) {
			error = err?.message || 'Failed to upload icon';
		} finally {
			uploading = false;
			input.value = '';
		}
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

	async function handleArchiveToggle() {
		if (!channel) return;
		archiving = true;
		error = '';
		try {
			const updated = isArchived
				? await unarchiveChannelApi(channel.id)
				: await archiveChannelApi(channel.id);
			reset();
			onsave(updated);
		} catch (e: any) {
			error = e?.message || (isArchived ? 'Failed to unarchive channel' : 'Failed to archive channel');
		} finally {
			archiving = false;
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
		// Resolve icon fields: custom upload takes precedence
		const useCustom = iconMode === 'upload' && customIconUrl;
		const iconValue = useCustom ? null : effectiveIcon;
		const iconUrlValue = useCustom ? customIconUrl : null;

		try {
			let ch: Channel;
			if (isEdit && channel) {
				ch = await updateChannelApi(channel.id, {
					name: trimmedName,
					topic: topic.trim() || undefined,
					description: description.trim() || undefined,
					icon: iconValue,
					icon_url: iconUrlValue
				});
			} else {
				ch = await createChannelApi({
					name: trimmedName,
					type: channelType,
					category_id: categoryId,
					topic: topic.trim() || undefined,
					description: description.trim() || undefined,
					icon: iconValue,
					icon_url: iconUrlValue
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
							class="type-btn"
							class:selected={channelType === 'VOICE'}
							class:disabled={!isVoiceVideoEnabled()}
							disabled={!isVoiceVideoEnabled()}
							onclick={() => channelType = 'VOICE'}
						>
							<span class="type-icon">🔊</span>
							<span class="type-info">
								<span class="type-name">Voice</span>
								<span class="type-desc">{isVoiceVideoEnabled() ? 'Voice & video chat' : 'Voice not configured'}</span>
							</span>
						</button>
						</div>
					</div>
				{/if}

				<!-- Icon Picker -->
				<div class="field">
					<label class="field-label">Icon</label>
					<div class="icon-mode-tabs">
						<button class="icon-mode-tab" class:active={iconMode === 'preset'} onclick={() => iconMode = 'preset'}>Preset</button>
						<button class="icon-mode-tab" class:active={iconMode === 'upload'} onclick={() => iconMode = 'upload'}>Upload</button>
					</div>

					{#if iconMode === 'preset'}
						<div class="icon-picker">
							{#each ICON_GROUPS as group}
								<div class="icon-group-label">{group.label}</div>
								<div class="icon-grid">
									{#each group.icons as icon}
										<button
											class="icon-btn"
											class:selected={effectiveIcon === icon}
											title={icon}
											onclick={() => { selectedIcon = icon; customIconUrl = null; }}
										>
											<span class="material-symbols-outlined">{icon}</span>
										</button>
									{/each}
								</div>
							{/each}
						</div>
					{:else}
						<div class="icon-upload">
							{#if customIconUrl}
								<div class="icon-preview">
									<img src={customIconUrl} alt="Custom icon" />
									<button class="icon-remove" onclick={() => customIconUrl = null} title="Remove">✕</button>
								</div>
							{/if}
							<label class="upload-btn">
								{uploading ? 'Uploading...' : customIconUrl ? 'Replace image' : 'Upload image'}
								<input type="file" accept="image/*" onchange={handleIconUpload} hidden disabled={uploading} />
							</label>
						</div>
					{/if}
				</div>

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

				{#if isArchivable}
					<div class="archive-section">
						<button
							class="archive-btn"
							class:unarchive={isArchived}
							onclick={handleArchiveToggle}
							disabled={archiving}
						>
							{archiving ? (isArchived ? 'Unarchiving...' : 'Archiving...') : (isArchived ? 'Unarchive Channel' : 'Archive Channel')}
						</button>
						<p class="archive-hint">{isArchived ? 'This channel is archived. No new messages can be sent.' : 'Archiving hides the channel and prevents new messages.'}</p>
					</div>
				{/if}

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

	/* Icon picker */
	.icon-mode-tabs {
		display: flex;
		gap: 0;
		margin-bottom: 0.5rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		overflow: hidden;
	}

	.icon-mode-tab {
		flex: 1;
		padding: 0.3rem 0.5rem;
		background: none;
		border: none;
		color: var(--text-muted);
		font-size: 0.75rem;
		cursor: pointer;
	}

	.icon-mode-tab:not(:last-child) {
		border-right: 1px solid var(--border);
	}

	.icon-mode-tab.active {
		background: var(--bg-active, rgba(255, 255, 255, 0.08));
		color: var(--text);
	}

	.icon-picker {
		max-height: 180px;
		overflow-y: auto;
		padding: 0.25rem;
		background: var(--bg, #1e1f22);
		border: 1px solid var(--border);
		border-radius: 4px;
	}

	.icon-group-label {
		font-size: 0.6rem;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		color: var(--text-muted);
		padding: 0.3rem 0.2rem 0.15rem;
	}

	.icon-grid {
		display: flex;
		flex-wrap: wrap;
		gap: 2px;
	}

	.icon-btn {
		width: 34px;
		height: 34px;
		display: flex;
		align-items: center;
		justify-content: center;
		border: 2px solid transparent;
		border-radius: 6px;
		background: none;
		color: var(--text-muted);
		cursor: pointer;
		padding: 0;
	}

	.icon-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	.icon-btn.selected {
		border-color: var(--accent, #5865f2);
		background: rgba(88, 101, 242, 0.15);
		color: var(--accent, #5865f2);
	}

	.icon-btn .material-symbols-outlined {
		font-size: 20px;
	}

	.icon-upload {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		padding: 0.5rem;
		background: var(--bg, #1e1f22);
		border: 1px solid var(--border);
		border-radius: 4px;
	}

	.icon-preview {
		position: relative;
		width: 40px;
		height: 40px;
		flex-shrink: 0;
	}

	.icon-preview img {
		width: 100%;
		height: 100%;
		object-fit: cover;
		border-radius: 6px;
	}

	.icon-remove {
		position: absolute;
		top: -6px;
		right: -6px;
		width: 18px;
		height: 18px;
		border-radius: 50%;
		background: var(--danger, #e74c3c);
		color: white;
		border: none;
		font-size: 10px;
		cursor: pointer;
		display: flex;
		align-items: center;
		justify-content: center;
		padding: 0;
		line-height: 1;
	}

	.upload-btn {
		padding: 0.35rem 0.75rem;
		border: 1px solid var(--border);
		border-radius: 4px;
		background: none;
		color: var(--text-muted);
		font-size: 0.75rem;
		cursor: pointer;
	}

	.upload-btn:hover {
		background: var(--bg-hover, rgba(255, 255, 255, 0.05));
		color: var(--text);
	}

	/* Archive section */
	.archive-section {
		padding: 0.75rem 0;
		border-top: 1px solid var(--border);
		margin-top: 0.5rem;
	}

	.archive-btn {
		padding: 0.4rem 0.75rem;
		border-radius: 4px;
		border: 1px solid var(--border);
		background: none;
		color: var(--text-muted);
		font-size: 0.8rem;
		cursor: pointer;
		transition: background 0.12s, color 0.12s, border-color 0.12s;
	}

	.archive-btn:hover:not(:disabled) {
		background: rgba(231, 76, 60, 0.1);
		border-color: var(--danger, #e74c3c);
		color: var(--danger, #e74c3c);
	}

	.archive-btn.unarchive:hover:not(:disabled) {
		background: rgba(46, 204, 113, 0.1);
		border-color: #2ecc71;
		color: #2ecc71;
	}

	.archive-btn:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.archive-hint {
		font-size: 0.7rem;
		color: var(--text-muted);
		margin: 0.35rem 0 0;
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
