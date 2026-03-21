<script lang="ts">
	import { isDesktop } from '$lib/platform/index.js';
	import {
		getVoiceKeybinds,
		updateVoiceKeybinds
	} from '$lib/platform/voice-shortcuts.js';
	import type { Keybinds } from '$lib/platform/shortcuts.js';
	import { onMount } from 'svelte';

	let keybinds = $state<Keybinds>({ pushToTalk: null, muteToggle: null, deafenToggle: null });
	let recording = $state<keyof Keybinds | null>(null);
	let saved = $state(false);

	onMount(() => {
		keybinds = getVoiceKeybinds();
	});

	function startRecording(field: keyof Keybinds) {
		recording = field;
	}

	function onKeydown(e: KeyboardEvent) {
		if (!recording) return;
		e.preventDefault();
		e.stopPropagation();

		// Build Tauri-compatible shortcut string
		const parts: string[] = [];
		if (e.ctrlKey || e.metaKey) parts.push('CommandOrControl');
		if (e.altKey) parts.push('Alt');
		if (e.shiftKey) parts.push('Shift');

		// Skip modifier-only keys
		const key = e.key;
		if (['Control', 'Meta', 'Alt', 'Shift'].includes(key)) return;

		// Map key to Tauri key code
		const mapped = mapKey(key);
		if (mapped) parts.push(mapped);

		if (parts.length > 0) {
			const shortcut = parts.join('+');
			keybinds = { ...keybinds, [recording]: shortcut };
			recording = null;
			save();
		}
	}

	function mapKey(key: string): string | null {
		if (key.length === 1) return key.toUpperCase();
		const map: Record<string, string> = {
			'F1': 'F1', 'F2': 'F2', 'F3': 'F3', 'F4': 'F4',
			'F5': 'F5', 'F6': 'F6', 'F7': 'F7', 'F8': 'F8',
			'F9': 'F9', 'F10': 'F10', 'F11': 'F11', 'F12': 'F12',
			' ': 'Space', 'Enter': 'Enter', 'Escape': 'Escape',
			'Backspace': 'Backspace', 'Tab': 'Tab',
			'ArrowUp': 'ArrowUp', 'ArrowDown': 'ArrowDown',
			'ArrowLeft': 'ArrowLeft', 'ArrowRight': 'ArrowRight',
			'`': 'Backquote'
		};
		return map[key] ?? null;
	}

	function clear(field: keyof Keybinds) {
		keybinds = { ...keybinds, [field]: null };
		save();
	}

	async function save() {
		await updateVoiceKeybinds(keybinds);
		saved = true;
		setTimeout(() => { saved = false; }, 1500);
	}
</script>

<svelte:window onkeydown={onKeydown} />

<div class="settings-card">
	<h1>Keybinds</h1>

	{#if !isDesktop()}
		<p class="muted">Global keybinds are only available in the desktop app.</p>
	{:else}
		<p class="muted">Configure global keyboard shortcuts for voice. These work even when the window is not focused.</p>

		<div class="keybind-list">
			{#each [
				{ key: 'pushToTalk' as const, label: 'Push to Talk' },
				{ key: 'muteToggle' as const, label: 'Mute Toggle' },
				{ key: 'deafenToggle' as const, label: 'Deafen Toggle' }
			] as item}
				<div class="keybind-row">
					<span class="keybind-label">{item.label}</span>
					<div class="keybind-value">
						{#if recording === item.key}
							<span class="recording">Press a key combo...</span>
						{:else if keybinds[item.key]}
							<kbd>{keybinds[item.key]}</kbd>
							<button class="btn-sm danger" onclick={() => clear(item.key)}>Clear</button>
						{:else}
							<span class="unset">Not set</span>
						{/if}
						{#if recording !== item.key}
							<button class="btn-sm" onclick={() => startRecording(item.key)}>
								{keybinds[item.key] ? 'Change' : 'Set'}
							</button>
						{/if}
					</div>
				</div>
			{/each}
		</div>

		{#if saved}
			<p class="saved">Saved</p>
		{/if}
	{/if}
</div>

<style>
	.keybind-list {
		display: flex;
		flex-direction: column;
		gap: 0.75rem;
		margin-top: 1rem;
	}

	.keybind-row {
		display: flex;
		align-items: center;
		justify-content: space-between;
		padding: 0.6rem 0.75rem;
		background: var(--bg);
		border: 1px solid var(--border);
		border-radius: 6px;
	}

	.keybind-label {
		font-size: 0.9rem;
		font-weight: 500;
	}

	.keybind-value {
		display: flex;
		align-items: center;
		gap: 0.5rem;
	}

	kbd {
		padding: 0.2rem 0.5rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 4px;
		font-family: monospace;
		font-size: 0.8rem;
	}

	.recording {
		color: var(--accent);
		font-size: 0.85rem;
		animation: pulse 1s ease-in-out infinite;
	}

	@keyframes pulse {
		0%, 100% { opacity: 1; }
		50% { opacity: 0.5; }
	}

	.unset {
		color: var(--text-muted);
		font-size: 0.85rem;
	}

	.saved {
		color: var(--success, #4caf50);
		font-size: 0.85rem;
		margin-top: 0.75rem;
	}

	.danger {
		color: var(--danger, #e74c3c) !important;
	}
</style>
