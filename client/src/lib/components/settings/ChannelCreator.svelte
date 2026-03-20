<script lang="ts">
	import { createChannel } from '$lib/api/channels.js';
	import { DEFAULT_ICONS, ICON_GROUPS } from '$lib/icons.js';
	import type { ApiError } from '$lib/api/client.js';

	interface PendingChannel {
		name: string;
		type: string;
		icon: string | null;
		created: boolean;
	}

	const TYPE_LABELS: Record<string, string> = {
		TEXT: 'Text',
		THREAD_CHANNEL: 'Threads',
		VOICE: 'Voice'
	};

	let channels = $state<PendingChannel[]>([
		{ name: 'general', type: 'TEXT', icon: null, created: false },
		{ name: 'off-topic', type: 'TEXT', icon: null, created: false },
		{ name: 'Voice', type: 'VOICE', icon: null, created: false }
	]);

	let newName = $state('');
	let newType = $state('TEXT');
	let error = $state('');
	let creating = $state(false);
	let iconPickerIndex = $state<number | null>(null);

	function addChannel() {
		if (!newName.trim()) return;
		channels = [...channels, { name: newName.trim(), type: newType, icon: null, created: false }];
		newName = '';
		newType = 'TEXT';
	}

	function removeChannel(index: number) {
		if (channels[index].created) return;
		if (iconPickerIndex === index) iconPickerIndex = null;
		channels = channels.filter((_, i) => i !== index);
	}

	function setIcon(index: number, icon: string | null) {
		channels[index] = { ...channels[index], icon };
		channels = [...channels];
		iconPickerIndex = null;
	}

	function toggleIconPicker(index: number) {
		iconPickerIndex = iconPickerIndex === index ? null : index;
	}

	function iconFor(channel: PendingChannel): string {
		return channel.icon ?? DEFAULT_ICONS[channel.type] ?? 'tag';
	}

	export async function createAll(): Promise<boolean> {
		creating = true;
		error = '';
		try {
			for (let i = 0; i < channels.length; i++) {
				if (channels[i].created) continue;
				await createChannel({
					name: channels[i].name,
					type: channels[i].type,
					icon: channels[i].icon
				});
				channels[i] = { ...channels[i], created: true };
				channels = [...channels];
			}
			return true;
		} catch (err) {
			error = (err as ApiError).message || 'Failed to create channel';
			return false;
		} finally {
			creating = false;
		}
	}

	const uncreatedCount = $derived(channels.filter(c => !c.created).length);
</script>

{#if error}
	<div class="error-message">{error}</div>
{/if}

<div class="channel-list">
	{#each channels as channel, i}
		<div class="channel-item" class:created={channel.created}>
			<button class="icon-btn" onclick={() => { if (!channel.created) toggleIconPicker(i); }} disabled={channel.created} title="Change icon">
				<span class="material-symbols-outlined">{iconFor(channel)}</span>
			</button>
			<span class="channel-name">{channel.name}</span>
			<span class="channel-type-badge">{TYPE_LABELS[channel.type] ?? channel.type}</span>
			{#if channel.created}
				<span class="created-badge">✓</span>
			{:else}
				<button class="btn-sm btn-danger" onclick={() => removeChannel(i)}>Remove</button>
			{/if}
		</div>

		{#if iconPickerIndex === i}
			<div class="icon-picker">
				{#each ICON_GROUPS as group}
					<div class="icon-group-label">{group.label}</div>
					<div class="icon-grid">
						{#each group.icons as ic}
							<button
								class="icon-option"
								class:active={iconFor(channel) === ic}
								onclick={() => setIcon(i, ic === DEFAULT_ICONS[channel.type] ? null : ic)}
								title={ic}
							>
								<span class="material-symbols-outlined">{ic}</span>
							</button>
						{/each}
					</div>
				{/each}
			</div>
		{/if}
	{/each}
</div>

<div class="add-row">
	<input type="text" bind:value={newName} placeholder="Channel name" onkeydown={(e) => { if (e.key === 'Enter') addChannel(); }} />
	<select bind:value={newType}>
		<option value="TEXT">Text</option>
		<option value="THREAD_CHANNEL">Threads</option>
		<option value="VOICE">Voice</option>
	</select>
	<button class="btn-sm" onclick={addChannel} disabled={!newName.trim()}>Add</button>
</div>

<style>
	.channel-list {
		display: flex;
		flex-direction: column;
		gap: 0.35rem;
		margin-bottom: 1.25rem;
	}

	.channel-item {
		display: flex;
		align-items: center;
		gap: 0.5rem;
		padding: 0.5rem 0.75rem;
		border-radius: 4px;
		font-size: 0.875rem;
		background: var(--bg);
		border: 1px solid var(--border);
	}

	.channel-item.created {
		opacity: 0.5;
	}

	.icon-btn {
		background: none;
		border: 1px solid transparent;
		border-radius: 4px;
		padding: 0.15rem;
		cursor: pointer;
		color: var(--text-muted);
		display: flex;
		align-items: center;
		justify-content: center;
		transition: border-color 0.1s, color 0.1s;
	}

	.icon-btn:hover:not(:disabled) {
		border-color: var(--border);
		color: var(--text);
	}

	.icon-btn:disabled {
		cursor: default;
		opacity: 0.5;
	}

	.icon-btn .material-symbols-outlined {
		font-size: 1.1rem;
	}

	.channel-name {
		flex: 1;
		font-weight: 500;
	}

	.channel-type-badge {
		font-size: 0.65rem;
		padding: 0.15rem 0.45rem;
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 3px;
		color: var(--text-muted);
		text-transform: uppercase;
		letter-spacing: 0.03em;
	}

	.created-badge {
		color: var(--success, #2ecc71);
		font-weight: 600;
	}

	/* Icon picker */
	.icon-picker {
		background: var(--bg-surface);
		border: 1px solid var(--border);
		border-radius: 6px;
		padding: 0.75rem;
		margin: 0.25rem 0 0.5rem 2rem;
	}

	.icon-group-label {
		font-size: 0.65rem;
		font-weight: 600;
		text-transform: uppercase;
		letter-spacing: 0.04em;
		color: var(--text-muted);
		margin: 0.5rem 0 0.25rem;
	}

	.icon-group-label:first-child {
		margin-top: 0;
	}

	.icon-grid {
		display: flex;
		flex-wrap: wrap;
		gap: 0.2rem;
	}

	.icon-option {
		background: none;
		border: 1px solid transparent;
		border-radius: 4px;
		padding: 0.3rem;
		cursor: pointer;
		color: var(--text-muted);
		display: flex;
		align-items: center;
		justify-content: center;
		transition: all 0.1s;
	}

	.icon-option:hover {
		background: var(--bg-hover);
		color: var(--text);
	}

	.icon-option.active {
		background: color-mix(in srgb, var(--accent) 15%, transparent);
		border-color: var(--accent);
		color: var(--accent);
	}

	.icon-option .material-symbols-outlined {
		font-size: 1.1rem;
	}

	/* Add row */
	.add-row {
		display: flex;
		gap: 0.5rem;
		align-items: center;
	}

	.add-row input {
		flex: 1;
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.5rem 0.6rem;
		font-size: 0.875rem;
		font-family: inherit;
	}

	.add-row select {
		background: var(--bg);
		border: 1px solid var(--border);
		color: var(--text);
		border-radius: 4px;
		padding: 0.5rem 0.6rem;
		font-size: 0.875rem;
		font-family: inherit;
	}
</style>
