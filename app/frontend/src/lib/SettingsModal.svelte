<script lang="ts">
	import { onMount } from 'svelte'
	import { GetSettings, SaveSettings } from '../../wailsjs/go/main/App'

	let { onClose }: { onClose: () => void } = $props()

	let autoStartOnLaunch = $state(false)
	let loading = $state(true)
	let saving = $state(false)

	onMount(async () => {
		const settings = await GetSettings()
		autoStartOnLaunch = settings.autoStartOnLaunch
		loading = false
	})

	async function toggleAutoStart(): Promise<void> {
		const next = !autoStartOnLaunch
		autoStartOnLaunch = next
		saving = true
		try {
			await SaveSettings({ autoStartOnLaunch: next })
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
	<div class="modal">
		<h3>Settings</h3>

		{#if loading}
			<p>Loading...</p>
		{:else}
			<label class="setting-row">
				<div class="setting-text">
					<div class="setting-label">Auto-start Minecraft server on launch</div>
					<div class="setting-hint">Skips straight to starting the server when you open Tree Engine.</div>
				</div>
				<input type="checkbox" checked={autoStartOnLaunch} disabled={saving} onchange={toggleAutoStart} />
			</label>

			<p class="more-hint">More settings (Minecraft version, Java version/path) are coming soon.</p>
		{/if}

		<div class="row">
			<button class="btn secondary" onclick={onClose}>Close</button>
		</div>
	</div>
</div>

<style>
	.modal-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.5);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 2000;
	}
	.modal {
		width: min(440px, 90vw);
		background: var(--bg);
		border: 1px solid var(--panel-border);
		border-radius: 8px;
		padding: 20px;
		box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
	}
	h3 {
		margin: 0 0 12px;
	}
	p {
		margin: 0 0 10px;
		color: var(--text-dim);
		font-size: 13px;
	}
	.setting-row {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 16px;
		background: var(--panel);
		border: 1px solid var(--panel-border);
		border-radius: 6px;
		padding: 12px;
		cursor: pointer;
	}
	.setting-text {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}
	.setting-label {
		font-size: 13px;
	}
	.setting-hint {
		font-size: 12px;
		color: var(--text-dim);
	}
	.setting-row input[type='checkbox'] {
		margin-top: 2px;
		flex-shrink: 0;
	}
	.more-hint {
		margin-top: 10px;
		margin-bottom: 0;
		font-size: 12px;
		font-style: italic;
	}
	.row {
		display: flex;
		gap: 10px;
		justify-content: flex-end;
		margin-top: 16px;
	}
</style>
