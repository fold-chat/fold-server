<script lang="ts">
	import { getRuntimeConfig, updateRuntimeConfig, type RuntimeConfig } from '$lib/api/config.js';
	import { getVoiceMode } from '$lib/stores/voice.svelte.js';
	import type { ApiError } from '$lib/api/client.js';

	let {
		onloaded,
		hideActions = false
	}: {
		onloaded?: () => void;
		hideActions?: boolean;
	} = $props();

	const mode = $derived(getVoiceMode());
	const isEnabled = $derived(mode !== 'off');

	let config = $state<RuntimeConfig>({});
	let loading = $state(false);
	let saving = $state(false);
	let error = $state('');
	let success = $state('');

	// Editable fields
	let editMode = $state('off');
	let editUrl = $state('');
	let editApiKey = $state('');
	let editApiSecret = $state('');
	let editCentralApiKey = $state('');
	let editWebhookUrl = $state('');
	let editMaxParticipants = $state('50');
	let editE2ee = $state('false');
	let editTurnEnabled = $state('false');

	const validationError = $derived.by(() => {
		if (editMode === 'managed') {
			if (!editCentralApiKey || editCentralApiKey.endsWith('...')) return 'API key is required for managed mode';
			if (!editWebhookUrl) return 'Webhook URL is required for managed mode';
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
		if (editMode === 'managed' && editWebhookUrl !== (cfg['fold.livekit.webhook-url'] ?? window.location.origin)) return true;
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

	/** Whether the current form state is valid (no missing required fields) */
	export function isValid(): boolean {
		return !validationError;
	}

	/** Whether the user has selected a non-off mode */
	export function isConfigured(): boolean {
		return editMode !== 'off';
	}

	/** Reset mode to off (for skip without saving incomplete config) */
	export function resetToOff() {
		editMode = 'off';
	}

	export async function loadConfig() {
		loading = true;
		error = '';
		try {
			const cfg = await getRuntimeConfig();
			config = cfg;
			hydrateFields(cfg);
			onloaded?.();
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
		editWebhookUrl = cfg['fold.livekit.webhook-url'] ?? window.location.origin;
		editMaxParticipants = cfg['fold.livekit.max-participants'] ?? '50';
		editE2ee = cfg['fold.livekit.e2ee'] ?? 'false';
		editTurnEnabled = cfg['fold.livekit.turn-enabled'] ?? 'false';
	}

	const modeChanging = $derived(editMode !== (config['fold.livekit.mode'] ?? 'off'));

	export async function save() {
		if (validationError) { error = validationError; return; }
		if (modeChanging && isEnabled && !confirm('Changing voice mode will drop all active voice calls. Continue?')) return;
		saving = true;
		error = '';
		success = '';
		try {
			const patch: RuntimeConfig = { 'fold.livekit.mode': editMode };
			if (editMode === 'managed') {
				if (editCentralApiKey && !editCentralApiKey.endsWith('...')) {
					patch['fold.livekit.central-api-key'] = editCentralApiKey;
				}
				if (editWebhookUrl) {
					patch['fold.livekit.webhook-url'] = editWebhookUrl;
				}
			}
			if (editMode === 'external') {
				if (editUrl) patch['fold.livekit.url'] = editUrl;
				if (editApiKey && !editApiKey.endsWith('...')) patch['fold.livekit.api-key'] = editApiKey;
				if (editApiSecret && !editApiSecret.endsWith('...')) patch['fold.livekit.api-secret'] = editApiSecret;
			}
			if (editMode !== 'off') {
				patch['fold.livekit.max-participants'] = editMaxParticipants;
				patch['fold.livekit.e2ee'] = editE2ee;
				patch['fold.livekit.turn-enabled'] = editTurnEnabled;
			}
			const result = await updateRuntimeConfig(patch);
			config = result;
			hydrateFields(result);
			success = 'Settings saved';
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save';
		} finally {
			saving = false;
			setTimeout(() => { success = ''; }, 3000);
		}
	}

	// Auto-enable E2EE when switching to managed
	$effect(() => {
		if (editMode === 'managed') editE2ee = 'true';
	});

	$effect(() => {
		loadConfig();
	});
</script>

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
</div>

<!-- Mode = managed -->
{#if editMode === 'managed'}
	<div class="form-section" style="margin-top: 1rem">
		<h3 class="subsection">Managed Setup</h3>
		<div class="form-group">
			<label for="central-api-key">API Key <span class="required">*</span></label>
			<input id="central-api-key" type="text" bind:value={editCentralApiKey}
				placeholder="fold_..." onfocus={() => { if (editCentralApiKey.endsWith('...')) editCentralApiKey = ''; }} />
		</div>
		<div class="form-group">
			<label for="webhook-url">Webhook URL <span class="required">*</span></label>
			<input id="webhook-url" type="text" bind:value={editWebhookUrl}
				placeholder="https://your-server.example.com" />
			<span class="hint">Public URL where central.fold.chat can reach this server for voice webhooks.</span>
		</div>
	</div>
{/if}

<!-- Mode = external -->
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

<!-- Common config -->
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

<!-- Save button (hidden in wizard mode) -->
{#if !hideActions && hasChanges}
	<div class="form-actions" style="margin-top: 1rem">
		{#if validationError}
			<span class="validation-hint">{validationError}</span>
		{/if}
		<button class="btn-primary" onclick={save} disabled={!canSave}>
			{saving ? 'Saving...' : 'Save'}
		</button>
	</div>
{/if}

<style>
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

	.subsection {
		font-size: 0.85rem;
		margin: 0 0 0.35rem;
		color: var(--text-muted);
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.03em;
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

	.required {
		color: #e74c3c;
		font-weight: 600;
	}

	.validation-hint {
		color: #e74c3c;
		font-size: 0.8rem;
		margin-right: 0.5rem;
	}

	.hint {
		font-size: 0.75rem;
		color: var(--text-muted);
		margin-top: 0.15rem;
	}
</style>
