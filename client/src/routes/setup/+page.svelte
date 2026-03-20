<script lang="ts">
	import { setupAccount } from '$lib/api/auth.js';
	import { getMe } from '$lib/api/users.js';
	import { setUser } from '$lib/stores/auth.svelte.js';
	import { updateServerSettings } from '$lib/api/settings.js';
	import { updateRuntimeConfig, type RuntimeConfig } from '$lib/api/config.js';
	import { apiRaw } from '$lib/api/client.js';
	import { goto } from '$app/navigation';
	import type { ApiError } from '$lib/api/client.js';
	import ServerIdentityForm from '$lib/components/settings/ServerIdentityForm.svelte';
	import ChannelCreator from '$lib/components/settings/ChannelCreator.svelte';
	import RolesManager from '$lib/components/settings/RolesManager.svelte';
	import VoiceConfig from '$lib/components/settings/VoiceConfig.svelte';
	import InviteCreator from '$lib/components/settings/InviteCreator.svelte';
	import '$lib/styles/settings.css';

	const STEPS = ['Restore', 'Account', 'Server', 'Channels', 'Roles', 'Voice', 'Klipy', 'Invite'];

	let currentStep = $state(0);
	let error = $state('');
	let loading = $state(false);

	// Step 0: Restore
	let backupFile = $state<File | null>(null);
	let backupPassword = $state('');
	let restoring = $state(false);

	// Step 1: Account
	let username = $state('');
	let password = $state('');
	let confirmPassword = $state('');

	// Step 2: Server Identity
	let serverName = $state('');
	let serverDescription = $state('');
	let serverIcon = $state('');
	let serverUrl = $state('');
	let identityError = $state('');
	let identitySuccess = $state('');

	// Step 6: Klipy
	let klipyApiKey = $state('');
	let klipySaving = $state(false);

	// Component refs
	let channelCreator = $state<ChannelCreator>();
	let voiceConfig = $state<VoiceConfig>();

	// Can't go back before account creation (step 1) since it's a one-way action,
	// and can't go back to restore (step 0) once we've started fresh.
	const canGoBack = $derived(currentStep >= 3);

	function next() {
		error = '';
		identityError = '';
		currentStep++;
	}

	function back() {
		if (!canGoBack) return;
		error = '';
		identityError = '';
		currentStep--;
	}

	// --- Step 0: Restore ---
	function onBackupFileChange(e: Event) {
		const input = e.target as HTMLInputElement;
		backupFile = input.files?.[0] ?? null;
	}

	async function handleRestore() {
		if (!backupFile) return;
		restoring = true;
		error = '';
		try {
			const formData = new FormData();
			formData.append('backup', backupFile);
			if (backupPassword) formData.append('password', backupPassword);
			await apiRaw('/setup/restore', { method: 'POST', body: formData });
			goto('/login');
		} catch (err) {
			error = (err as ApiError).message || 'Restore failed';
		} finally {
			restoring = false;
		}
	}

	// --- Step 1: Account ---
	async function handleCreateAccount() {
		error = '';
		if (password !== confirmPassword) {
			error = 'Passwords do not match';
			return;
		}
		loading = true;
		try {
			await setupAccount(username, password);
			const user = await getMe();
			setUser(user);
			serverUrl = window.location.origin;
			next();
		} catch (err) {
			error = (err as ApiError).message || (err as ApiError).error || 'Setup failed';
		} finally {
			loading = false;
		}
	}

	// --- Step 2: Server Identity ---
	async function handleSaveIdentity() {
		loading = true;
		identityError = '';
		identitySuccess = '';
		try {
			const data: Record<string, string | null> = {
				server_name: serverName.trim() || null,
				server_description: serverDescription.trim() || null,
				server_icon: serverIcon || null
			};
			if (serverUrl.trim()) data.server_url = serverUrl.trim();
			await updateServerSettings(data);
			next();
		} catch (err) {
			identityError = (err as ApiError).message || 'Failed to save';
		} finally {
			loading = false;
		}
	}

	// --- Step 3: Channels ---
	async function handleCreateChannels() {
		if (!channelCreator) return;
		const ok = await channelCreator.createAll();
		if (ok) next();
	}

	// --- Step 6: Klipy ---
	async function handleSaveKlipy() {
		klipySaving = true;
		error = '';
		try {
			const patch: RuntimeConfig = { 'fold.media.klipy-api-key': klipyApiKey.trim() };
			await updateRuntimeConfig(patch);
			next();
		} catch (err) {
			error = (err as ApiError).message || 'Failed to save';
		} finally {
			klipySaving = false;
		}
	}

	// --- Step 5: Voice ---
	async function handleVoiceContinue() {
		if (!voiceConfig) return;
		if (voiceConfig.isConfigured()) {
			await voiceConfig.save();
		}
		next();
	}

	function handleVoiceSkip() {
		if (voiceConfig && voiceConfig.isConfigured() && !voiceConfig.isValid()) {
			voiceConfig.resetToOff();
		}
		next();
	}

	const voiceReady = $derived(
		!voiceConfig?.isConfigured() || voiceConfig?.isValid()
	);

	// --- Finish ---
	function finish() {
		goto('/');
	}
</script>

<div class="auth-page">
	<div class="setup-wizard">
		<!-- Progress -->
		<div class="progress-bar">
			{#each STEPS as step, i}
				<div class="progress-step" class:active={i === currentStep} class:done={i < currentStep}>
					<span class="step-num">{i + 1}</span>
				</div>
				{#if i < STEPS.length - 1}
					<div class="progress-line" class:done={i < currentStep}></div>
				{/if}
			{/each}
		</div>
		<div class="step-label">{STEPS[currentStep]}</div>

		<div class="wizard-card">
			{#if error}
				<div class="error-message">{error}</div>
			{/if}

			<!-- Step 0: Restore -->
			{#if currentStep === 0}
				<h2>Welcome to Fold</h2>
				<p class="muted">Fold is an open-source, self-hosted community platform. This wizard will walk you through setting up your server for the first time.</p>

				<div class="section-divider"></div>

				<h3>Restore from Backup</h3>
				<p class="muted">If you're migrating from another Fold server or restoring from a backup, you can upload it here. This will restore all your data including users, channels, messages, and files. The server will restart after a successful restore.</p>

				<div class="form-group">
					<label for="backup-file">Backup File</label>
					<input id="backup-file" type="file" accept=".zip,.tar,.tar.gz" onchange={onBackupFileChange} />
				</div>
				{#if backupFile}
					<div class="form-group">
						<label for="backup-pw">Backup Password</label>
						<input id="backup-pw" type="password" bind:value={backupPassword} placeholder="Leave empty if not encrypted" />
						<span class="hint">Only needed if the backup was created with a password.</span>
					</div>
					<div class="form-actions">
						<button class="btn-primary" onclick={handleRestore} disabled={restoring}>
							{restoring ? 'Restoring...' : 'Restore Backup'}
						</button>
					</div>
				{/if}

				<div class="section-divider"></div>

				<p class="muted">No backup? No problem — let's set up a brand new server.</p>
				<div class="form-actions">
					<button class="btn-primary" onclick={next} style="width: 100%">Start Fresh</button>
				</div>

			<!-- Step 1: Create Account -->
			{:else if currentStep === 1}
				<h2>Create Admin Account</h2>
				<p class="muted">This will be the <strong>owner</strong> account for your server. The owner has full control over all settings, roles, and members. Choose a strong password — this account cannot be deleted.</p>

				<form onsubmit={(e) => { e.preventDefault(); handleCreateAccount(); }}>
					<div class="form-group">
						<label for="username">Username</label>
						<input id="username" type="text" bind:value={username} autocomplete="username" required />
						<span class="hint">2-32 characters. Letters, numbers, underscores, and hyphens only.</span>
					</div>
					<div class="form-group">
						<label for="password">Password</label>
						<input id="password" type="password" bind:value={password} autocomplete="new-password" required />
						<span class="hint">Minimum 8 characters.</span>
					</div>
					<div class="form-group">
						<label for="confirmPassword">Confirm Password</label>
						<input id="confirmPassword" type="password" bind:value={confirmPassword} autocomplete="new-password" required />
					</div>
					<div class="form-actions">
						<button type="submit" class="btn-primary" disabled={loading} style="width:100%">
							{loading ? 'Creating...' : 'Create Account'}
						</button>
					</div>
				</form>

			<!-- Step 2: Server Identity -->
			{:else if currentStep === 2}
				<h2>Server Identity</h2>
				<p class="muted">Give your community a name, description, and icon. This is what members will see when they first join. The <strong>Server URL</strong> is the public address where people access your server — it's been pre-filled based on how you're accessing it now.</p>

				<div>
					<ServerIdentityForm
						bind:name={serverName}
						bind:description={serverDescription}
						bind:icon={serverIcon}
						bind:serverUrl={serverUrl}
						showUrl={true}
						onsave={handleSaveIdentity}
						saving={loading}
						bind:error={identityError}
						bind:success={identitySuccess}
					/>
				</div>

			<!-- Step 3: Channels -->
			{:else if currentStep === 3}
				<h2>Create Channels</h2>
				<p class="muted">Channels are where conversations happen. <strong>Text</strong> channels are for messages, <strong>Threads</strong> channels work like a forum with individual discussion threads, and <strong>Voice</strong> channels are for calls.</p>
				<p class="muted" style="margin-top: 0.5rem">We've added some sensible defaults. Feel free to add, remove, or change their icons. You can always create more channels later.</p>

				<div>
					<ChannelCreator bind:this={channelCreator} />
				</div>
				<div class="wizard-nav">
					<button class="btn-back" onclick={back}>&larr; Back</button>
					<button class="btn-primary" onclick={handleCreateChannels}>Create & Continue</button>
				</div>

			<!-- Step 4: Roles -->
			{:else if currentStep === 4}
				<h2>Configure Roles</h2>
				<p class="muted">Roles control what members can do on your server — like sending messages, managing channels, or banning users. Every new member gets the <strong>default</strong> role automatically. You can create additional roles here, or skip this and set them up later in <strong>Settings &gt; Roles</strong>.</p>

				<div>
					<RolesManager />
				</div>
				<div class="wizard-nav">
					<button class="btn-back" onclick={back}>&larr; Back</button>
					<button class="btn-primary" onclick={next}>Continue</button>
					<span class="nav-spacer"></span>
					<button class="btn-skip" onclick={next}>Skip — configure later in Settings</button>
				</div>

			<!-- Step 5: Voice -->
			{:else if currentStep === 5}
				<h2>Voice & Video</h2>
				<p class="muted">Fold supports real-time voice and video calls powered by LiveKit. You can run LiveKit <strong>embedded</strong> alongside Fold (requires the <code>livekit-server</code> binary), connect to an <strong>external</strong> LiveKit server you manage, or use <strong>managed</strong> hosting via central.fold.chat. <a href="https://fold.chat/getting-started/voice-and-video/" target="_blank" rel="noopener">Learn more</a></p>
				<p class="muted" style="margin-top: 0.5rem">If you're not sure, you can skip this and enable it later in <strong>Settings &gt; Voice</strong>.</p>

				<div>
					<VoiceConfig bind:this={voiceConfig} hideActions={true} />
				</div>
				<div class="wizard-nav">
					<button class="btn-back" onclick={back}>&larr; Back</button>
					<button class="btn-primary" onclick={handleVoiceContinue} disabled={!voiceReady}>
						{voiceConfig?.isConfigured() ? 'Save & Continue' : 'Continue'}
					</button>
					<span class="nav-spacer"></span>
					<button class="btn-skip" onclick={handleVoiceSkip}>Skip — enable later in Settings</button>
				</div>

			<!-- Step 6: Klipy -->
			{:else if currentStep === 6}
				<h2>GIF Search</h2>
				<p class="muted">Fold uses <a href="https://klipy.com" target="_blank" rel="noopener">Klipy</a> to power GIF search in the message composer. To enable it, sign up for a free API key at <a href="https://klipy.com" target="_blank" rel="noopener">klipy.com</a> and paste it below.</p>
				<p class="muted" style="margin-top: 0.5rem">This is optional — your server works fine without GIFs. You can add a key later in <strong>Settings &gt; Media</strong>.</p>

				<div class="form-group">
					<label for="klipy-key">Klipy API Key</label>
					<input id="klipy-key" type="text" bind:value={klipyApiKey} placeholder="Enter API key" />
				</div>
				<div class="wizard-nav">
					<button class="btn-back" onclick={back}>&larr; Back</button>
					<button class="btn-primary" onclick={handleSaveKlipy} disabled={!klipyApiKey.trim() || klipySaving}>
						{klipySaving ? 'Saving...' : 'Save & Continue'}
					</button>
					<span class="nav-spacer"></span>
					<button class="btn-skip" onclick={next}>Skip — configure later in Settings</button>
				</div>

			<!-- Step 7: Invite -->
			{:else if currentStep === 7}
				<h2>Invite People</h2>
				<p class="muted">Your server is ready! Create an invite link to share with the people you'd like to join. Each invite can have a description to help you remember where you shared it. You can manage all your invites later in <strong>Settings &gt; Invites</strong>.</p>

				<div>
					<InviteCreator showMaxUses={false} />
				</div>
				<div class="wizard-nav" style="margin-top: 1.5rem">
					<button class="btn-back" onclick={back}>&larr; Back</button>
					<button class="btn-primary" onclick={finish}>Finish Setup</button>
					<span class="nav-spacer"></span>
					<button class="btn-skip" onclick={finish}>Skip — create invites later</button>
				</div>
			{/if}
		</div>
	</div>
	<div class="powered-by">Powered by <a href="https://fold.chat" target="_blank" rel="noopener">fold.chat</a></div>
</div>

<style>
	.setup-wizard {
		width: 100%;
		max-width: 640px;
		display: flex;
		flex-direction: column;
		gap: 1rem;
		margin-top: auto;
	}

	/* --- Progress bar --- */
	.progress-bar {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 0;
		padding: 0 1rem;
	}

	.progress-step {
		width: 30px;
		height: 30px;
		border-radius: 50%;
		display: flex;
		align-items: center;
		justify-content: center;
		font-size: 0.7rem;
		font-weight: 600;
		background: var(--bg-surface);
		border: 2px solid var(--border);
		color: var(--text-muted);
		flex-shrink: 0;
		transition: all 0.2s;
	}

	.progress-step.active {
		border-color: var(--accent);
		color: var(--accent);
		background: color-mix(in srgb, var(--accent) 15%, transparent);
	}

	.progress-step.done {
		border-color: var(--success, #2ecc71);
		background: var(--success, #2ecc71);
		color: white;
	}

	.progress-line {
		flex: 1;
		height: 2px;
		background: var(--border);
		max-width: 32px;
		transition: background 0.2s;
	}

	.progress-line.done {
		background: var(--success, #2ecc71);
	}

	.step-label {
		text-align: center;
		font-size: 0.7rem;
		color: var(--text-muted);
		text-transform: uppercase;
		letter-spacing: 0.06em;
		font-weight: 600;
	}

	.step-num {
		line-height: 1;
	}

	/* --- Card --- */
	.wizard-card {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: var(--radius, 8px);
		padding: 2rem;
	}

	.wizard-card h2 {
		font-size: 1.25rem;
		margin: 0 0 0.5rem;
	}

	.wizard-card h3 {
		font-size: 1rem;
		margin: 0 0 0.35rem;
	}

	/* More breathing room for description paragraphs */
	.wizard-card > .muted {
		line-height: 1.5;
		margin-bottom: 0.25rem;
	}

	/* Sections within a step (embedded components, forms) */
	.wizard-card > div {
		margin-top: 1.25rem;
	}

	/* --- Navigation footer --- */
	.wizard-nav {
		display: flex;
		align-items: center;
		gap: 0.75rem;
		margin-top: 1.5rem;
		padding-top: 1.25rem;
		border-top: 1px solid var(--border);
		flex-wrap: wrap;
	}

	.btn-skip {
		all: unset;
		color: var(--text-muted);
		font-size: 0.8rem;
		cursor: pointer;
		font-family: inherit;
	}

	.btn-skip:hover {
		color: var(--accent);
		text-decoration: underline;
		background: none;
	}

	.btn-back {
		background: none;
		border: 1px solid var(--border);
		color: var(--text-muted);
		font-size: 0.8rem;
		cursor: pointer;
		padding: 0.5rem 0.85rem;
		font-family: inherit;
		border-radius: 4px;
		white-space: nowrap;
	}

	.btn-back:hover {
		color: var(--text);
		border-color: var(--text-muted);
	}

	.nav-spacer {
		flex: 1;
	}

	/* --- Utility --- */
	.section-divider {
		border: none;
		border-top: 1px solid var(--border);
		margin: 1.5rem 0;
	}

	.wizard-card .hint {
		font-size: 0.75rem;
		color: var(--text-muted);
		margin-top: 0.2rem;
		line-height: 1.4;
	}

	/* Override global button reset inside wizard for btn-primary to use settings sizing */
	.wizard-card .btn-primary {
		padding: 0.5rem 1.25rem;
		font-size: 0.875rem;
		border-radius: 4px;
	}

	/* Override global form-group margin for tighter settings-like spacing */
	.wizard-card .form-group {
		margin-bottom: 1.25rem;
	}

	.wizard-card .form-actions {
		margin-top: 0.75rem;
	}

	/* Match settings card code style */
	.wizard-card code {
		background: var(--bg);
		padding: 0.1rem 0.35rem;
		border-radius: 3px;
		font-size: 0.8rem;
	}
</style>
