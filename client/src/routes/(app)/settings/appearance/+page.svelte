<script lang="ts">
	import {
		BUILT_IN_THEMES,
		BUILT_IN_THEME_COLORS,
		getThemePreference,
		setThemePreference,
		getCustomThemes,
		saveCustomTheme,
		deleteCustomTheme,
		previewCustomTheme,
		type CustomTheme,
		type CustomThemeColors,
	} from '$lib/stores/theme.svelte.js';
	import { getDensity, setDensity, type Density } from '$lib/stores/density.svelte.js';
	import ConfirmDialog from '$lib/components/ConfirmDialog.svelte';
	import { exportTheme, decodeTheme, type DecodeResult } from '$lib/utils/theme-codec.js';
	import { contrastRatio } from '$lib/utils/color.js';
	import { isDesktop, setGlobalTheme } from '$lib/platform/index.js';

	// ── Density ───────────────────────────────────────────────────────────────
	const DENSITY_OPTIONS: { id: Density; label: string; icon: string; desc: string }[] = [
		{ id: 'compact', label: 'Compact', icon: 'density_small', desc: 'Tighter spacing, smaller text' },
		{ id: 'default', label: 'Default', icon: 'density_medium', desc: 'Balanced spacing' },
		{ id: 'large',   label: 'Large',   icon: 'density_large',  desc: 'More breathing room' },
	];

	// ── Custom theme editor ───────────────────────────────────────────────────
	type ColorKey = Exclude<keyof CustomThemeColors, 'radius'>;

	const TOKEN_GROUPS: { group: string; tokens: { key: ColorKey; label: string }[] }[] = [
		{
			group: 'Background',
			tokens: [
				{ key: 'bg', label: 'Background' },
				{ key: 'bg-surface', label: 'Surface' },
				{ key: 'bg-input', label: 'Input' },
			],
		},
		{
			group: 'Text',
			tokens: [
				{ key: 'text', label: 'Primary' },
				{ key: 'text-muted', label: 'Muted' },
			],
		},
		{
			group: 'Accents',
			tokens: [
				{ key: 'accent', label: 'Accent' },
				{ key: 'border', label: 'Border' },
			],
		},
		{
			group: 'Status',
			tokens: [
				{ key: 'success', label: 'Success' },
				{ key: 'danger', label: 'Danger' },
			],
		},
	];

	let editingTheme = $state<CustomTheme | null>(null);
	let priorPreference = $state('system');
	let deleteDialog = $state<{ open: boolean; id: string; name: string }>({ open: false, id: '', name: '' });

	// ── Import / export ───────────────────────────────────────────────────────
	let importPanel = $state(false);
	let importStr = $state('');
	let copiedId = $state<string | null>(null);

	const importDecoded = $derived<DecodeResult | null>(
		importStr.trim() ? decodeTheme(importStr.trim()) : null
	);

	const importResolvedName = $derived(
		importDecoded && !('error' in importDecoded)
			? resolveNameCollision(importDecoded.theme.name)
			: null
	);

	function resolveNameCollision(name: string): string {
		const names = new Set(getCustomThemes().map((t) => t.name));
		if (!names.has(name)) return name;
		let n = 2;
		while (names.has(`${name} (${n})`)) n++;
		return `${name} (${n})`;
	}

	async function copyExport(ct: CustomTheme): Promise<void> {
		await navigator.clipboard.writeText(exportTheme(ct));
		copiedId = ct.id;
		setTimeout(() => { copiedId = null; }, 2000);
	}

	function addImported(): void {
		if (!importDecoded || 'error' in importDecoded) return;
		const theme = { ...importDecoded.theme, name: importResolvedName ?? importDecoded.theme.name };
		saveCustomTheme(theme);
		setThemePreference(theme.id);
		importStr = '';
		importPanel = false;
	}

	function startingColors(): CustomThemeColors {
		const resolved = getThemePreference();
		const builtIn = BUILT_IN_THEME_COLORS[resolved as keyof typeof BUILT_IN_THEME_COLORS];
		if (builtIn) return { ...builtIn };
		const custom = getCustomThemes().find((t) => t.id === resolved);
		return { ...(custom?.colors ?? BUILT_IN_THEME_COLORS.dark) };
	}

	function openNew(): void {
		priorPreference = getThemePreference();
		editingTheme = { id: crypto.randomUUID(), name: 'My Theme', colors: startingColors() };
		doPreview();
	}

	function openEdit(theme: CustomTheme): void {
		priorPreference = getThemePreference();
		editingTheme = { ...theme, colors: { ...theme.colors } };
		doPreview();
	}

	function openDuplicate(colors: CustomThemeColors, baseName: string): void {
		priorPreference = getThemePreference();
		editingTheme = { id: crypto.randomUUID(), name: `${baseName} Copy`, colors: { ...colors } };
		doPreview();
	}

	function doPreview(): void {
		if (!editingTheme) return;
		const c = editingTheme.colors;
		previewCustomTheme({
			id: editingTheme.id,
			name: editingTheme.name,
			colors: {
				bg: c.bg, 'bg-surface': c['bg-surface'], 'bg-input': c['bg-input'],
				text: c.text, 'text-muted': c['text-muted'],
				accent: c.accent, border: c.border,
				success: c.success, danger: c.danger,
				radius: c.radius,
			},
		});
	}

	function onColorInput(key: ColorKey, value: string): void {
		if (!editingTheme) return;
		editingTheme.colors[key] = value;
		doPreview();
	}

	function onRadiusInput(value: string): void {
		if (!editingTheme) return;
		editingTheme.colors.radius = `${value}px`;
		doPreview();
	}

	function save(): void {
		if (!editingTheme) return;
		const theme = editingTheme;
		saveCustomTheme({ ...theme, colors: { ...theme.colors } });
		setThemePreference(theme.id);
		editingTheme = null;
	}

	function cancel(): void {
		const prior = priorPreference;
		editingTheme = null;
		setThemePreference(prior);
	}

	function confirmDelete(id: string, name: string): void {
		deleteDialog = { open: true, id, name };
	}

	function radiusNum(r: string): number {
		return parseInt(r) || 8;
	}

	function customPreviewStyle(colors: CustomThemeColors): string {
		return `background:${colors.bg};border-color:${colors.border}`;
	}

	function customSurfaceStyle(colors: CustomThemeColors): string {
		return `background:${colors['bg-surface']};border-color:${colors.border}`;
	}

	// ── Phase 5: contrast warnings ────────────────────────────────────────────
	function isContrastLow(key: ColorKey): boolean {
		if (!editingTheme || (key !== 'text' && key !== 'text-muted')) return false;
		return contrastRatio(editingTheme.colors[key], editingTheme.colors.bg) < 4.5;
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Appearance</h1>
	</div>

	<div class="form-section">
		<!-- ── Theme picker ──────────────────────────────────────────────── -->
		<div class="form-group" class:faded={editingTheme !== null}>
			<!-- svelte-ignore a11y_label_has_associated_control -->
			<label>Theme</label>
			<div class="theme-grid">
				<!-- System -->
				<button
					class="theme-card"
					class:active={getThemePreference() === 'system'}
					onclick={() => setThemePreference('system')}
				>
					<div class="theme-preview system-preview">
						<span class="material-symbols-outlined" style="font-size:28px;color:#888">computer</span>
					</div>
					<span class="theme-label">System</span>
					{#if getThemePreference() === 'system'}
						<span class="active-badge">
							<span class="material-symbols-outlined" style="font-size:14px">check</span>
						</span>
					{/if}
				</button>

				<!-- Built-in themes -->
				{#each BUILT_IN_THEMES as theme (theme.id)}
					<div
						class="theme-card"
						class:active={getThemePreference() === theme.id}
					>
						<button
							class="builtin-select"
							onclick={() => setThemePreference(theme.id)}
							aria-label="Select {theme.label}"
						>
							<div class="theme-preview" style="background:{theme.colors.bg};border-color:{theme.colors.border}">
								<div class="preview-surface" style="background:{theme.colors.surface};border-color:{theme.colors.border}">
									<div class="preview-text-line" style="background:{theme.colors.text}"></div>
									<div class="preview-text-line short" style="background:{theme.colors.text}"></div>
								</div>
								<div class="preview-accent-bar" style="background:{theme.colors.accent}"></div>
							</div>
							<span class="theme-label">{theme.label}</span>
							{#if getThemePreference() === theme.id}
								<span class="active-badge">
									<span class="material-symbols-outlined" style="font-size:14px">check</span>
								</span>
							{/if}
						</button>
						<button
							class="clone-btn icon-btn"
							title="Clone as custom theme"
							onclick={() => openDuplicate(BUILT_IN_THEME_COLORS[theme.id], theme.label)}
						>
							<span class="material-symbols-outlined">content_copy</span>
						</button>
					</div>
				{/each}
			</div>

			<!-- Custom themes -->
			{#if getCustomThemes().length > 0}
				<p class="section-label muted">Custom</p>
				<div class="theme-grid">
					{#each getCustomThemes() as ct (ct.id)}
						<div class="custom-card" class:active={getThemePreference() === ct.id}>
							<button
								class="custom-select"
								onclick={() => setThemePreference(ct.id)}
								aria-label="Select {ct.name}"
							>
								<div class="theme-preview" style={customPreviewStyle(ct.colors)}>
									<div class="preview-surface" style={customSurfaceStyle(ct.colors)}>
										<div class="preview-text-line" style="background:{ct.colors.text}"></div>
										<div class="preview-text-line short" style="background:{ct.colors.text}"></div>
									</div>
									<div class="preview-accent-bar" style="background:{ct.colors.accent}"></div>
								</div>
								<span class="theme-label">{ct.name}</span>
								{#if getThemePreference() === ct.id}
									<span class="active-badge">
										<span class="material-symbols-outlined" style="font-size:14px">check</span>
									</span>
								{/if}
							</button>
							<div class="custom-actions">
								<button class="icon-btn" title="Edit" onclick={() => openEdit(ct)}>
									<span class="material-symbols-outlined">edit</span>
								</button>
								<button class="icon-btn" title={copiedId === ct.id ? 'Copied!' : 'Export'} onclick={() => copyExport(ct)}>
									<span class="material-symbols-outlined">{copiedId === ct.id ? 'check' : 'share'}</span>
								</button>
								<button class="icon-btn" title="Duplicate" onclick={() => openDuplicate(ct.colors, ct.name)}>
									<span class="material-symbols-outlined">content_copy</span>
								</button>
								<button class="icon-btn danger" title="Delete" onclick={() => confirmDelete(ct.id, ct.name)}>
									<span class="material-symbols-outlined">delete</span>
								</button>
							</div>
						</div>
					{/each}

					<!-- New Theme card -->
					<button class="theme-card new-card" onclick={openNew}>
						<div class="theme-preview new-preview">
							<span class="material-symbols-outlined" style="font-size:28px;color:var(--text-muted)">add</span>
						</div>
						<span class="theme-label">New Theme</span>
					</button>
				</div>
			{:else}
				<button class="new-theme-btn" onclick={openNew}>
					<span class="material-symbols-outlined" style="font-size:16px;vertical-align:-3px">add</span>
					New Custom Theme
				</button>
			{/if}

			<!-- Import -->
			<button class="import-toggle-btn" onclick={() => { importPanel = !importPanel; importStr = ''; }}>
				<span class="material-symbols-outlined" style="font-size:14px;vertical-align:-2px">download</span>
				Import Theme
			</button>
			{#if importPanel}
				<div class="import-panel">
					<!-- svelte-ignore a11y_autofocus -->
					<input
						type="text"
						class="import-input"
						bind:value={importStr}
						placeholder="Paste kt1.… export string"
						autofocus
					/>
					{#if importDecoded}
						{#if 'error' in importDecoded}
							<p class="import-error">{importDecoded.error}</p>
						{:else}
							<div class="import-preview-row">
								<div class="mini-preview" style={customPreviewStyle(importDecoded.theme.colors)}>
									<div class="mini-surface" style={customSurfaceStyle(importDecoded.theme.colors)}></div>
									<div class="mini-accent" style="background:{importDecoded.theme.colors.accent}"></div>
								</div>
								<div class="import-meta">
									<span class="import-name">{importResolvedName}</span>
									{#if importResolvedName !== importDecoded.theme.name}
										<span class="muted" style="font-size:0.7rem">Renamed from "{importDecoded.theme.name}"</span>
									{/if}
								</div>
								<button class="btn-primary" style="font-size:0.8rem;padding:0.3rem 0.75rem" onclick={addImported}>Add</button>
							</div>
						{/if}
					{/if}
				</div>
			{/if}
		</div>

		<!-- ── Custom theme editor ───────────────────────────────────────── -->
		{#if editingTheme !== null}
			<div class="editor-panel">
				<div class="editor-header">
					<span class="material-symbols-outlined" style="font-size:18px">palette</span>
					<span>{getCustomThemes().some(t => t.id === editingTheme!.id) ? 'Edit' : 'New'} Custom Theme</span>
				</div>

				<!-- Name -->
				<div class="editor-row">
					<!-- svelte-ignore a11y_label_has_associated_control -->
					<label class="editor-label">Name</label>
					<input
						type="text"
						class="name-input"
						bind:value={editingTheme.name}
						maxlength="40"
						placeholder="My Theme"
					/>
				</div>

				<!-- Color token groups -->
				<div class="token-grid">
					{#each TOKEN_GROUPS as group}
						<div class="token-group">
							<span class="group-label muted">{group.group}</span>
							{#each group.tokens as token}
								<div class="token-row">
									<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
									<label
										class="color-swatch-btn"
										title={token.label}
										onkeydown={(e) => { if (e.key === 'Enter') (e.currentTarget.querySelector('input') as HTMLElement)?.click(); }}
									>
										<div class="swatch" style="background:{editingTheme.colors[token.key]}"></div>
										<input
											type="color"
											value={editingTheme.colors[token.key]}
											oninput={(e) => onColorInput(token.key, e.currentTarget.value)}
										/>
									</label>
									<span class="token-name">{token.label}</span>
									{#if isContrastLow(token.key)}
										<span class="contrast-warn" title="Low contrast vs background (WCAG AA: 4.5:1)">
											<span class="material-symbols-outlined" style="font-size:14px">warning</span>
										</span>
									{/if}
								</div>
							{/each}
						</div>
					{/each}
				</div>

				<!-- Radius -->
				<div class="editor-row radius-row">
					<!-- svelte-ignore a11y_label_has_associated_control -->
					<label class="editor-label">Corner Radius</label>
					<div class="radius-wrap">
						<input
							type="range"
							min="0"
							max="20"
							step="1"
							value={radiusNum(editingTheme.colors.radius)}
							oninput={(e) => onRadiusInput(e.currentTarget.value)}
							class="radius-slider"
						/>
						<span class="radius-val muted">{editingTheme.colors.radius}</span>
					</div>
				</div>

				<!-- Actions -->
				<div class="editor-actions">
					<button class="btn-sm" onclick={cancel}>Cancel</button>
					{#if getCustomThemes().some(t => t.id === editingTheme!.id)}
						<button class="btn-sm export-btn" title="Copy export string" onclick={() => copyExport(editingTheme!)}>
							<span class="material-symbols-outlined" style="font-size:14px;vertical-align:-2px">{copiedId === editingTheme!.id ? 'check' : 'share'}</span>
							{copiedId === editingTheme!.id ? 'Copied!' : 'Export'}
						</button>
					{/if}
					<button class="btn-primary" onclick={save}>Save Theme</button>
				</div>
			</div>
		{/if}

		<!-- ── Density ────────────────────────────────────────────────────── -->
		<div class="form-group" class:faded={editingTheme !== null}>
			<!-- svelte-ignore a11y_label_has_associated_control -->
			<label>Message Density</label>
			<div class="density-group">
				{#each DENSITY_OPTIONS as opt (opt.id)}
					<button
						class="density-btn"
						class:active={getDensity() === opt.id}
						onclick={() => setDensity(opt.id)}
						title={opt.desc}
					>
						<span class="material-symbols-outlined" style="font-size:20px">{opt.icon}</span>
						<span>{opt.label}</span>
					</button>
				{/each}
			</div>
			<p class="density-hint muted">{DENSITY_OPTIONS.find(o => o.id === getDensity())?.desc}</p>
		</div>

		<!-- ── Apply to all servers (desktop only) ─────────────────────── -->
		{#if isDesktop()}
			<div class="form-group" class:faded={editingTheme !== null}>
				<button
					class="btn-primary global-theme-btn"
					onclick={() => setGlobalTheme(getThemePreference(), getCustomThemes())}
				>
					<span class="material-symbols-outlined" style="font-size:16px;vertical-align:-3px">sync</span>
					Apply theme to all servers
				</button>
				<p class="muted" style="font-size:0.75rem;margin-top:0.25rem">Sets this theme across every Fold server in the desktop app.</p>
			</div>
		{/if}

		<!-- ── Reset ──────────────────────────────────────────────────────── -->
		<div class="reset-row">
			<button class="btn-sm" onclick={() => setThemePreference('system')}>Reset to system theme</button>
		</div>
	</div>
</div>

<ConfirmDialog
	open={deleteDialog.open}
	title="Delete Theme"
	message={`Delete "${deleteDialog.name}"? This cannot be undone.`}
	confirmLabel="Delete"
	onconfirm={() => { deleteCustomTheme(deleteDialog.id); deleteDialog.open = false; }}
	oncancel={() => { deleteDialog.open = false; }}
/>

<style>
	/* ── Shared ─────────────────────────────────────────────────────────────── */
	.faded {
		opacity: 0.4;
		pointer-events: none;
		user-select: none;
	}

	.section-label {
		font-size: 0.7rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.05em;
		margin-top: 0.75rem;
		margin-bottom: 0.1rem;
	}

	/* ── Built-in theme cards ───────────────────────────────────────────────── */
	.builtin-select {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.5rem;
		padding: 0.6rem;
		background: none;
		border: none;
		cursor: pointer;
		color: var(--text-muted);
		width: 100%;
		position: relative;
	}

	.builtin-select:hover {
		background: none;
	}

	.theme-card.active .builtin-select { color: var(--text); }

	.clone-btn {
		position: absolute;
		top: 0.35rem;
		left: 0.35rem;
		opacity: 0;
		transition: opacity 0.12s;
		background: var(--bg-surface) !important;
		border: 1px solid var(--border) !important;
		z-index: 1;
	}
	.theme-card:hover .clone-btn { opacity: 1; }

	/* when theme-card is a div wrapper (built-in cards) remove its own padding */
	.theme-card:has(.builtin-select) {
		padding: 0;
		gap: 0;
		cursor: default;
	}

	/* ── Theme grid
	/* ── Theme grid ─────────────────────────────────────────────────────────── */
	.theme-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(110px, 1fr));
		gap: 0.75rem;
		margin-top: 0.25rem;
	}

	.theme-card {
		position: relative;
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.5rem;
		padding: 0.6rem;
		background: none;
		border: 2px solid var(--border);
		border-radius: 10px;
		cursor: pointer;
		transition: border-color 0.15s, background 0.15s;
		color: var(--text-muted);
	}

	.theme-card:hover {
		border-color: var(--accent);
		background: none;
	}

	.theme-card.active {
		border-color: var(--accent);
		color: var(--text);
	}

	.theme-preview {
		width: 100%;
		aspect-ratio: 16 / 10;
		border-radius: 6px;
		border: 1px solid transparent;
		overflow: hidden;
		display: flex;
		flex-direction: column;
		justify-content: flex-end;
		padding: 6px;
		gap: 4px;
		box-sizing: border-box;
	}

	.system-preview {
		background: linear-gradient(135deg, #1a1a2e 50%, #f5f5f5 50%);
		align-items: center;
		justify-content: center;
		border: 1px solid var(--border);
	}

	.preview-surface {
		flex: 1;
		border-radius: 4px;
		border: 1px solid transparent;
		padding: 5px 6px;
		display: flex;
		flex-direction: column;
		gap: 3px;
		justify-content: center;
	}

	.preview-text-line {
		height: 3px;
		border-radius: 2px;
		opacity: 0.7;
	}

	.preview-text-line.short { width: 60%; }

	.preview-accent-bar {
		height: 4px;
		border-radius: 2px;
		flex-shrink: 0;
	}

	.theme-label {
		font-size: 0.75rem;
		font-weight: 500;
		text-align: center;
		line-height: 1.2;
		word-break: break-word;
	}

	.active-badge {
		position: absolute;
		top: 0.35rem;
		right: 0.35rem;
		background: var(--accent);
		color: white;
		border-radius: 50%;
		width: 18px;
		height: 18px;
		display: flex;
		align-items: center;
		justify-content: center;
		line-height: 1;
	}

	/* ── Custom theme cards ─────────────────────────────────────────────────── */
	.custom-card {
		position: relative;
		display: flex;
		flex-direction: column;
		border: 2px solid var(--border);
		border-radius: 10px;
		overflow: hidden;
		transition: border-color 0.15s;
	}

	.custom-card.active { border-color: var(--accent); }
	.custom-card:hover { border-color: var(--accent); }

	.custom-select {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 0.5rem;
		padding: 0.6rem;
		background: none;
		border: none;
		cursor: pointer;
		color: var(--text-muted);
		width: 100%;
	}

	.custom-select:hover {
		background: none;
	}

	.custom-card.active .custom-select { color: var(--text); }

	.custom-actions {
		display: flex;
		justify-content: center;
		gap: 0.15rem;
		padding: 0.2rem 0.4rem 0.4rem;
		border-top: 1px solid var(--border);
	}

	.icon-btn {
		display: flex;
		align-items: center;
		justify-content: center;
		width: 26px;
		height: 26px;
		background: none;
		border: none;
		border-radius: 4px;
		cursor: pointer;
		color: var(--text-muted);
		padding: 0;
		transition: background 0.12s, color 0.12s;
	}

	.icon-btn :global(.material-symbols-outlined) { font-size: 15px; }

	.icon-btn:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.icon-btn.danger:hover {
		background: rgba(231, 76, 60, 0.12);
		color: var(--danger);
	}

	/* ── Import ─────────────────────────────────────────────────────────────── */
	.import-toggle-btn {
		margin-top: 0.4rem;
		width: fit-content;
		padding: 0.3rem 0.7rem;
		background: none;
		border: none;
		color: var(--text-muted);
		font-size: 0.78rem;
		cursor: pointer;
		transition: color 0.12s;
		border-radius: 4px;
	}

	.import-toggle-btn:hover {
		color: var(--accent);
		background: var(--bg-hover);
	}

	.import-panel {
		margin-top: 0.4rem;
		display: flex;
		flex-direction: column;
		gap: 0.4rem;
	}

	.import-input {
		width: 100% !important;
		background: var(--bg-input) !important;
		border: 1px solid var(--border) !important;
		border-radius: 4px;
		padding: 0.35rem 0.6rem !important;
		color: var(--text);
		font-size: 0.8rem;
		font-family: monospace;
		outline: none;
	}

	.import-input:focus { border-color: var(--accent) !important; }

	.import-error {
		font-size: 0.78rem;
		color: var(--danger);
		margin: 0;
	}

	.import-preview-row {
		display: flex;
		align-items: center;
		gap: 0.6rem;
		padding: 0.4rem 0.5rem;
		background: var(--bg-hover);
		border-radius: 6px;
	}

	.mini-preview {
		width: 54px;
		height: 34px;
		flex-shrink: 0;
		border-radius: 5px;
		border: 1px solid transparent;
		padding: 4px;
		box-sizing: border-box;
		display: flex;
		flex-direction: column;
		justify-content: flex-end;
		gap: 3px;
	}

	.mini-surface {
		flex: 1;
		border-radius: 3px;
	}

	.mini-accent {
		height: 3px;
		border-radius: 2px;
		flex-shrink: 0;
	}

	.import-meta {
		flex: 1;
		display: flex;
		flex-direction: column;
		gap: 0.1rem;
		min-width: 0;
	}

	.import-name {
		font-size: 0.85rem;
		font-weight: 500;
		color: var(--text);
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	/* ── New theme ───────────────────────────────────────────────────────────── */
	.new-card { border-style: dashed; }

	.new-preview {
		background: none;
		border: none;
		display: flex;
		align-items: center;
		justify-content: center;
	}

	.new-theme-btn {
		margin-top: 0.5rem;
		width: fit-content;
		padding: 0.4rem 0.85rem;
		background: none;
		border: 1px dashed var(--border);
		border-radius: 6px;
		color: var(--text-muted);
		font-size: 0.8rem;
		cursor: pointer;
		transition: border-color 0.15s, color 0.15s;
	}

	.new-theme-btn:hover {
		border-color: var(--accent);
		color: var(--accent);
		background: none;
	}

	/* ── Editor panel ────────────────────────────────────────────────────────── */
	.editor-panel {
		background: var(--bg);
		border: 1px solid var(--accent);
		border-radius: 10px;
		padding: 1rem;
		display: flex;
		flex-direction: column;
		gap: 0.85rem;
	}

	.editor-header {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		font-size: 0.875rem;
		font-weight: 600;
		color: var(--accent);
	}

	.editor-row {
		display: flex;
		align-items: center;
		gap: 0.75rem;
	}

	.editor-label {
		font-size: 0.7rem;
		font-weight: 600;
		color: var(--text-muted);
		text-transform: uppercase;
		min-width: 90px;
		flex-shrink: 0;
	}

	.name-input {
		flex: 1;
		background: var(--bg-input);
		border: 1px solid var(--border);
		border-radius: 4px;
		padding: 0.35rem 0.6rem;
		color: var(--text);
		font-size: 0.875rem;
		font-family: inherit;
		outline: none;
		width: auto !important;
	}

	.name-input:focus { border-color: var(--accent); }

	/* 2×2 grid of token groups */
	.token-grid {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 0.6rem 1.5rem;
	}

	.token-group {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
	}

	.group-label {
		font-size: 0.68rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.04em;
	}

	.token-row {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}

	.token-name {
		font-size: 0.8rem;
		color: var(--text-muted);
		flex: 1;
	}

	.contrast-warn {
		color: color-mix(in srgb, var(--danger) 80%, transparent);
		display: flex;
		align-items: center;
		flex-shrink: 0;
	}

	/* Color swatch + hidden native input */
	.color-swatch-btn {
		position: relative;
		width: 26px;
		height: 22px;
		cursor: pointer;
		flex-shrink: 0;
		display: block;
	}

	.swatch {
		width: 100%;
		height: 100%;
		border-radius: 4px;
		border: 2px solid var(--border);
		display: block;
	}

	.color-swatch-btn input[type='color'] {
		position: absolute;
		inset: 0;
		opacity: 0;
		cursor: pointer;
		width: 100% !important;
		height: 100% !important;
		padding: 0 !important;
		border: none !important;
		background: none !important;
	}

	/* Radius */
	.radius-wrap {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		flex: 1;
	}

	.radius-slider {
		flex: 1;
		width: auto !important;
		height: 4px;
		accent-color: var(--accent);
		cursor: pointer;
		background: none !important;
		border: none !important;
		border-radius: 0 !important;
		padding: 0 !important;
	}

	.radius-val {
		font-size: 0.8rem;
		min-width: 2.5rem;
		text-align: right;
	}

	.editor-actions {
		display: flex;
		justify-content: flex-end;
		gap: 0.5rem;
		padding-top: 0.25rem;
		border-top: 1px solid var(--border);
	}

	/* ── Density ─────────────────────────────────────────────────────────────── */
	.density-group {
		display: flex;
		gap: 0;
		margin-top: 0.25rem;
		border: 1px solid var(--border);
		border-radius: 8px;
		overflow: hidden;
		width: fit-content;
	}

	.density-btn {
		display: flex;
		align-items: center;
		gap: 0.4rem;
		padding: 0.5rem 1rem;
		background: none;
		border: none;
		border-right: 1px solid var(--border);
		color: var(--text-muted);
		font-size: 0.8rem;
		cursor: pointer;
		transition: background 0.12s, color 0.12s;
		border-radius: 0;
	}

	.density-btn:last-child { border-right: none; }

	.density-btn:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.density-btn.active {
		background: color-mix(in srgb, var(--accent) 12%, transparent);
		color: var(--accent);
	}

	.density-hint {
		margin-top: 0.35rem;
		font-size: 0.75rem;
	}

	/* ── Export button in editor actions ─────────────────────────────────────── */
	.export-btn {
		margin-right: auto;
	}

	/* ── Global theme button ─────────────────────────────────────────────────── */
	.global-theme-btn {
		display: inline-flex;
		align-items: center;
		gap: 0.4rem;
		font-size: 0.85rem;
	}

	/* ── Reset ───────────────────────────────────────────────────────────────── */
	.reset-row {
		display: flex;
		justify-content: flex-end;
		padding-top: 0.25rem;
	}
</style>
