<script lang="ts">
	import { onMount } from 'svelte'
	import { GetSettings, SaveSettings } from '../../wailsjs/go/main/App'
	import { instance } from '../../wailsjs/go/models'
	import Icon from './Icon.svelte'

	let { onClose }: { onClose: () => void } = $props()

	// Kept whole (not just the one field this screen edits) so saving never
	// clobbers other settings, like the recent-projects list, that this modal
	// doesn't have a control for.
	let settings = $state<instance.Settings | undefined>(undefined)
	let loading = $state(true)
	let saving = $state(false)

	onMount(async () => {
		settings = await GetSettings()
		loading = false
	})

	async function toggleAutoStart(): Promise<void> {
		if (!settings) return
		settings.autoStartOnLaunch = !settings.autoStartOnLaunch
		saving = true
		try {
			await SaveSettings(settings)
		} finally {
			saving = false
		}
	}
</script>

<div
	class="modal-backdrop"
	role="button"
	tabindex="0"
	onclick={(e) => {
		if (e.target === e.currentTarget) onClose()
	}}
	onkeydown={(e) => {
		if (e.key === 'Escape') onClose()
	}}
>
	<div class="modal settings-modal">
		<div class="modal-head">
			<div class="modal-title"><Icon name="sliders" size={15} />Settings</div>
			<button class="icon-btn" aria-label="Close" onclick={onClose}><Icon name="close" size={15} /></button>
		</div>

		<div class="modal-body">
			{#if loading}
				<div class="empty-state">
					<Icon name="spinner" size={26} class="spin" />
					<div class="empty-copy">Loading settings...</div>
				</div>
			{:else if settings}
				<div class="eyebrow">Startup</div>
				<label class="setting-row" class:on={settings.autoStartOnLaunch}>
					<div class="setting-text">
						<div class="setting-label">Auto-start Minecraft server on launch</div>
						<div class="setting-hint">Skips straight to starting the server when you open Tree Engine.</div>
					</div>
					<input type="checkbox" checked={settings.autoStartOnLaunch} disabled={saving} onchange={toggleAutoStart} />
				</label>

				<div class="soon">
					<Icon name="info" size={14} />
					<span>Minecraft version and Java version/path settings are coming soon.</span>
				</div>
			{/if}
		</div>

		<div class="modal-foot">
			<button class="btn secondary" onclick={onClose}>Close</button>
		</div>
	</div>
</div>

<style>
	.settings-modal {
		width: min(470px, 92vw);
	}

	.setting-row {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 16px;
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 13px 14px;
		cursor: pointer;
		transition: border-color var(--fast), background var(--fast);
	}

	.setting-row:hover {
		border-color: var(--line-strong);
	}

	.setting-row.on {
		border-color: var(--accent-line);
		background: linear-gradient(180deg, var(--accent-wash), transparent), var(--bg-sunken);
	}

	.setting-text {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.setting-label {
		font-size: 12.5px;
		font-weight: 550;
		color: var(--text);
	}

	.setting-hint {
		font-size: 11.5px;
		color: var(--text-faint);
		line-height: 1.45;
	}

	.soon {
		display: flex;
		align-items: center;
		gap: 8px;
		font-size: 11.5px;
		color: var(--text-faint);
		padding: 2px;
	}
</style>
