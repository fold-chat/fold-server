<script lang="ts">
	import {
		BUILT_IN_THEMES,
		getThemePreference,
		setThemePreference,
		type ThemePreference
	} from '$lib/stores/theme.svelte.js';
	
	function select(id: ThemePreference) {
		setThemePreference(id);
	}
</script>

<div class="settings-card">
	<div class="header-row">
		<h1>Appearance</h1>
	</div>

	<div class="form-section">
		<div class="form-group">
			<label>Theme</label>
			<div class="theme-grid">
				<!-- System option -->
				<button
					class="theme-card"
					class:active={getThemePreference() === 'system'}
					onclick={() => select('system')}
				>
					<div class="theme-preview system-preview">
						<span class="material-symbols-outlined" style="font-size: 28px; color: #888">computer</span>
					</div>
					<span class="theme-label">System</span>
					{#if getThemePreference() === 'system'}
						<span class="active-badge">
							<span class="material-symbols-outlined" style="font-size: 14px">check</span>
						</span>
					{/if}
				</button>

				<!-- Built-in themes -->
				{#each BUILT_IN_THEMES as theme (theme.id)}
					<button
						class="theme-card"
						class:active={getThemePreference() === theme.id}
						onclick={() => select(theme.id)}
					>
						<div class="theme-preview" style="background: {theme.colors.bg}; border-color: {theme.colors.border}">
							<div class="preview-surface" style="background: {theme.colors.surface}; border-color: {theme.colors.border}">
								<div class="preview-text-line" style="background: {theme.colors.text}"></div>
								<div class="preview-text-line short" style="background: {theme.colors.text}"></div>
							</div>
							<div class="preview-accent-bar" style="background: {theme.colors.accent}"></div>
						</div>
						<span class="theme-label">{theme.label}</span>
						{#if getThemePreference() === theme.id}
							<span class="active-badge">
								<span class="material-symbols-outlined" style="font-size: 14px">check</span>
							</span>
						{/if}
					</button>
				{/each}
			</div>
		</div>
	</div>
</div>

<style>
	.theme-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
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
		background: var(--bg-hover);
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

	.preview-text-line.short {
		width: 60%;
	}

	.preview-accent-bar {
		height: 4px;
		border-radius: 2px;
		flex-shrink: 0;
	}

	.theme-label {
		font-size: 0.75rem;
		font-weight: 500;
		text-align: center;
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
</style>
