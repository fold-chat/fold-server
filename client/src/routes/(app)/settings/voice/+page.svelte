<script lang="ts">
	import { hasServerPermission } from '$lib/stores/auth.svelte.js';
	import { PermissionName } from '$lib/permissions.js';
	import type { ApiError } from '$lib/api/client.js';
	import { getVoiceStats, rotateKey, type VoiceStats } from '$lib/api/voice.js';
	import { getRuntimeConfig, updateRuntimeConfig, type RuntimeConfig } from '$lib/api/config.js';
	import { getVoiceMode, isE2eeCapability } from '$lib/stores/voice.svelte.js';
	import { getChannels } from '$lib/stores/channels.svelte.js';

	const canManageServer = $derived(hasServerPermission(PermissionName.MANAGE_SERVER));
	const mode = $derived(getVoiceMode());
	const isEnabled = $derived(mode !== 'off');

	let voiceStats = $state<VoiceStats | null>(null);
	let config = $state<RuntimeConfig>({});
	let loading = $state(false);
	let saving = $state(false);
	let error = $state('');
	let success = $state('');
	let rotatingKey = $state<string | null>(null);

	// Editable fields (local form state)
	let editMode = $state('off');
	let editUrl = $state('');
	let editApiKey = $state('');
	let editApiSecret = $state('');
	let editCentralApiKey = $state('');
	let editMaxParticipants = $state('50');
	let editE2ee = $state('false');
	let editTurnEnabled = $state('false');

	const voiceChannels = $derived(getChannels().filter(c => c.type === 'VOICE'));

	// Validation
	const validationError = $derived.by(() => {
		if (editMode === 'managed') {
			if (!editCentralApiKey || editCentralApiKey.endsWith('...')) return 'API key is required for managed mode';
		}
		if (editMode === 'external') {
			if (!editUrl) return 'URL is required for external mode';
			if (!editApiKey || editApiKey.endsWith('...')) return 'API key is required for external mode';
			if (!editApiSecret || editApiSecret.endsWith('...')) return 'API secret is required for external mode';
		}
		return null;
	});
	const canSave = $derived(!saving && !validationError);
	const hasChanges = $derived.by(() => {
		const cfg = config;
		if (editMode !== (cfg['fold.livekit.mode'] ?? 'off')) return true;
		if (editMode === 'managed' && editCentralApiKey !== (cfg['fold.livekit.central-api-key'] ?? '')) return true;
		if (editMode === 'external') {
			if (editUrl !== (cfg['fold.livekit.url'] ?? '')) return true;
			if (editApiKey !== (cfg['fold.livekit.api-key'] ?? '')) return true;
			if (editApiSecret !== (cfg['fold.livekit.api-secret'] ?? '')) return true;
		}
		if (editMaxParticipants !== (cfg['fold.livekit.max-participants'] ?? '50')) return true;
		if (editE2ee !== (cfg['fold.livekit.e2ee'] ?? 'false')) return true;
		if (editTurnEnabled !== (cfg['fold.livekit.turn-enabled'] ?? 'false')) return true;
		return false;
	});

	async function loadAll() {
		loading = true;
		error = '';
		try {
			const [stats, cfg] = await Promise.all([getVoiceStats(), getRuntimeConfig()]);
			voiceStats = stats;
			config = cfg;
			hydrateFields(cfg);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to load voice settings';
		} finally {
			loading = false;
		}
	}

	function hydrateFields(cfg: RuntimeConfig) {
		editMode = cfg['fold.livekit.mode'] ?? 'off';
		editUrl = cfg['fold.livekit.url'] ?? '';
		editApiKey = cfg['fold.livekit.api-key'] ?? '';
		editApiSecret = cfg['fold.livekit.api-secret'] ?? '';
		editCentralApiKey = cfg['fold.livekit.central-api-key'] ?? '';
		editMaxParticipants = cfg['fold.livekit.max-participants'] ?? '50';
		editE2ee = cfg['fold.livekit.e2ee'] ?? 'false';
		editTurnEnabled = cfg['fold.livekit.turn-enabled'] ?? 'false';
	}

	const modeChanging = $derived(editMode !== (config['fold.livekit.mode'] ?? 'off'));

	async function save() {
		if (validationError) { error = validationError; return; }
		if (modeChanging && isEnabled && !confirm('Changing voice mode will drop all active voice calls. Continue?')) return;
		saving = true;
		error = '';
		success = '';
		try {
			const patch: RuntimeConfig = { 'fold.livekit.mode': editMode };
			// Mode-specific fields
			if (editMode === 'managed') {
				if (editCentralApiKey && !editCentralApiKey.endsWith('...')) {
					patch['fold.livekit.central-api-key'] = editCentralApiKey;
				}
			}
			if (editMode === 'external') {
				if (editUrl) patch['fold.livekit.url'] = editUrl;
				if (editApiKey && !editApiKey.endsWith('...')) patch['fold.livekit.api-key'] = editApiKey;
				if (editApiSecret && !editApiSecret.endsWith('...')) patch['fold.livekit.api-secret'] = editApiSecret;
			}
			// Common settings (always included when not off)
			if (editMode !== 'off') {
				patch['fold.livekit.max-participants'] = editMaxParticipants;
				patch['fold.livekit.e2ee'] = editE2ee;
				patch['fold.livekit.turn-enabled'] = editTurnEnabled;
			}
			const result = await updateRuntimeConfig(patch);
			config = result;
			hydrateFields(result);
			success = 'Settings saved';
			try { voiceStats = await getVoiceStats(); } catch { /* ignore */ }
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save';
		} finally {
			saving = false;
			setTimeout(() => { success = ''; }, 3000);
		}
	}

	async function handleRotateKey(channelId: string) {
		rotatingKey = channelId;
		try {
			await rotateKey(channelId);
		} catch (err) {
			error = (err as ApiError).message || 'Failed to rotate key';
		} finally {
			rotatingKey = null;
		}
	}

	// Auto-enable E2EE when switching to managed
	$effect(() => {
		if (editMode === 'managed') editE2ee = 'true';
	});

	$effect(() => {
		if (canManageServer) loadAll();
	});
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Voice & Video</h1>
		<button class="btn-sm" onclick={loadAll} disabled={loading}>
			{loading ? 'Loading...' : 'Refresh'}
		</button>
	</div>

	{#if !canManageServer}
		<p class="muted">You don't have permission to manage voice settings.</p>
	{:else}
		{#if error}
			<div class="error-message">{error}</div>
		{/if}
		{#if success}
			<div class="success-message">{success}</div>
		{/if}

		<!-- Mode descriptions -->
		<div class="mode-descriptions">
			<p class="muted"><strong>Embedded</strong> — runs LiveKit alongside Fold. Requires <code>livekit-server</code> binary.</p>
			<p class="muted"><strong>External</strong> — connect to your own LiveKit server with URL + credentials.</p>
			<p class="muted"><strong>Managed</strong> — hosted by central.fold.chat. Just enter an API key.</p>
		</div>

		<!-- Mode selector -->
		<div class="form-section">
			<div class="form-group">
				<label for="voice-mode">Voice Mode</label>
				<select id="voice-mode" bind:value={editMode} class="mode-select">
					<option value="off">Off</option>
					<option value="embedded">Embedded</option>
					<option value="external">External</option>
					<option value="managed">Managed (central.fold.chat)</option>
				</select>
			</div>

			{#if editMode === 'embedded' && voiceStats && !voiceStats.embedded_binary_available}
				<div class="warning-message">LiveKit binary not found. Install <code>livekit-server</code> or set <code>FOLD_LIVEKIT_PATH</code>.</div>
			{/if}

			<!-- Status indicator -->
			{#if voiceStats}
				<div class="status-row">
					<span class="status-dot" class:status-up={voiceStats.status === 'UP'} class:status-off={voiceStats.status === 'OFF'}></span>
					<span class="status-text">
						{voiceStats.status === 'UP' ? 'Active' : 'Off'}
						{#if voiceStats.managed_status}
							— {voiceStats.managed_status}
						{/if}
					</span>
				</div>
			{/if}
		</div>

		<!-- Mode = managed: API key -->
		{#if editMode === 'managed'}
			<div class="form-section" style="margin-top: 1rem">
				<h3 class="subsection">Managed Setup</h3>
				<div class="form-group">
					<label for="central-api-key">API Key <span class="required">*</span></label>
					<input id="central-api-key" type="text" bind:value={editCentralApiKey}
						placeholder="fold_..." onfocus={() => { if (editCentralApiKey.endsWith('...')) editCentralApiKey = ''; }} />
				</div>
			</div>
		{/if}

		<!-- Mode = external: URL + key + secret -->
		{#if editMode === 'external'}
			<div class="form-section" style="margin-top: 1rem">
				<h3 class="subsection">External LiveKit</h3>
				<div class="form-group">
					<label for="ext-url">URL <span class="required">*</span></label>
					<input id="ext-url" type="text" bind:value={editUrl} placeholder="wss://livekit.example.com" />
				</div>
				<div class="form-group">
					<label for="ext-api-key">API Key <span class="required">*</span></label>
					<input id="ext-api-key" type="text" bind:value={editApiKey}
						onfocus={() => { if (editApiKey.endsWith('...')) editApiKey = ''; }} />
				</div>
				<div class="form-group">
					<label for="ext-api-secret">API Secret <span class="required">*</span></label>
					<input id="ext-api-secret" type="text" bind:value={editApiSecret}
						onfocus={() => { if (editApiSecret.endsWith('...')) editApiSecret = ''; }} />
				</div>
			</div>
		{/if}

		<!-- Common config: max-participants, e2ee, turn (when any mode enabled) -->
		{#if editMode !== 'off'}
			<div class="form-section" style="margin-top: 1rem">
				<h3 class="subsection">Voice Settings</h3>
				<div class="form-group">
					<label for="max-participants">Max Participants per Room</label>
					<input id="max-participants" type="number" bind:value={editMaxParticipants} min="2" max="200" />
				</div>
				<div class="toggle-row">
					<label>
						<input type="checkbox" checked={editE2ee === 'true'} onchange={() => editE2ee = editE2ee === 'true' ? 'false' : 'true'} />
						End-to-end encryption (E2EE)
					</label>
				</div>
				{#if editMode === 'embedded'}
					<div class="toggle-row">
						<label>
							<input type="checkbox" checked={editTurnEnabled === 'true'} onchange={() => editTurnEnabled = editTurnEnabled === 'true' ? 'false' : 'true'} />
							TURN relay (helps with restrictive firewalls)
						</label>
					</div>
				{/if}
			</div>
		{/if}

		<!-- Single save button -->
		{#if hasChanges}
			<div class="form-actions" style="margin-top: 1rem">
				{#if validationError}
					<span class="validation-hint">{validationError}</span>
				{/if}
				<button class="btn-primary" onclick={save} disabled={!canSave}>
					{saving ? 'Saving...' : 'Save'}
				</button>
			</div>
		{/if}

		<!-- Stats (when active) -->
		{#if isEnabled && voiceStats}
			<div class="form-section" style="margin-top: 1rem">
				<h3 class="subsection">Stats</h3>
				<div class="voice-stats">
					<div class="stat-row">
						<span class="stat-label">Active Connections</span>
						<span class="stat-value">{voiceStats.active_connections}</span>
					</div>
					<div class="stat-row">
						<span class="stat-label">Active Rooms</span>
						<span class="stat-value">{voiceStats.active_rooms}</span>
					</div>
					{#if voiceStats.rooms.length > 0}
						{#each voiceStats.rooms as room}
							{@const ch = getChannels().find(c => c.id === room.channel_id)}
							<div class="stat-row">
								<span class="stat-label">🔊 {ch?.name ?? room.room_name}</span>
								<span class="stat-value">{room.participants} participants</span>
							</div>
						{/each}
					{/if}
				</div>
			</div>
		{/if}

		<!-- E2EE Key Rotation -->
		{#if isEnabled && voiceChannels.length > 0 && isE2eeCapability()}
			<div class="form-section" style="margin-top: 1rem">
				<h3 class="subsection">E2EE Key Rotation</h3>
				<p class="muted" style="margin-bottom: 0.5rem">Force-rotate the encryption key for a voice channel.</p>
				<div class="key-rotation-list">
					{#each voiceChannels as vc}
						<div class="key-rotation-row">
							<span class="key-channel-name">🔊 {vc.name}</span>
							<button class="btn-sm" onclick={() => handleRotateKey(vc.id)} disabled={rotatingKey === vc.id}>
								{rotatingKey === vc.id ? 'Rotating...' : 'Rotate Key'}
							</button>
						</div>
					{/each}
				</div>
			</div>
		{/if}
	{/if}
</div>

<style>
	.voice-stats {
		display: flex;
		flex-direction: column;
		gap: 0.3rem;
	}

	.stat-row {
		display: flex;
		justify-content: space-between;
		padding: 0.35rem 0;
		border-bottom: 1px solid var(--border);
		font-size: 0.85rem;
	}

	.stat-label {
		color: var(--text-muted);
	}

	.stat-value {
		color: var(--text);
		font-weight: 500;
	}

	.subsection {
		font-size: 0.85rem;
		margin: 0 0 0.35rem;
		color: var(--text-muted);
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.03em;
	}

	.mode-row {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}

	.mode-select {
		flex: 1;
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.5rem 0.6rem;
		font-size: 0.875rem;
		font-family: inherit;
	}

	.status-row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		margin-top: 0.5rem;
	}

	.status-dot {
		width: 8px;
		height: 8px;
		border-radius: 50%;
		background: var(--text-muted);
	}

	.status-up {
		background: #2ecc71;
	}

	.status-off {
		background: var(--text-muted);
	}

	.status-text {
		font-size: 0.85rem;
		color: var(--text-muted);
	}

	.warning-message {
		color: #f39c12;
		font-size: 0.85rem;
		padding: 0.5rem;
		background: rgba(243, 156, 18, 0.1);
		border-radius: 4px;
	}

	.warning-message code {
		background: rgba(255, 255, 255, 0.08);
		padding: 0.1rem 0.3rem;
		border-radius: 2px;
		font-size: 0.8rem;
	}

	.mode-descriptions {
		margin-bottom: 0.75rem;
	}

	.mode-descriptions p {
		margin: 0.3rem 0;
	}

	.toggle-row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-size: 0.875rem;
	}

	.toggle-row label {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		cursor: pointer;
		color: var(--text);
	}

	.toggle-row input[type='checkbox'] {
		width: 16px;
		height: 16px;
		cursor: pointer;
	}

	.key-rotation-list {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}

	.key-rotation-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.4rem 0.5rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 4px;
	}

	.key-channel-name {
		font-size: 0.85rem;
	}

	.required {
		color: #e74c3c;
		font-weight: 600;
	}

	.validation-hint {
		color: #e74c3c;
		font-size: 0.8rem;
		margin-right: 0.5rem;
	}
</style>
