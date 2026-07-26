<script lang="ts">
	import { onMount } from 'svelte'
	import {
		GetAvailableVersions,
		GetSettings,
		OpenInstanceFolder,
		SaveSettings,
		SwapServerVersion,
	} from '../../wailsjs/go/main/App'
	import { instance } from '../../wailsjs/go/models'
	import Icon from './Icon.svelte'

	let { onClose }: { onClose: () => void } = $props()

	type Tab = 'server' | 'startup' | 'system'
	let activeTab = $state<Tab>('server')

	let settings = $state<instance.Settings | undefined>(undefined)
	let versions = $state<instance.VersionStatus[]>([])
	let loading = $state(true)
	let saving = $state(false)
	let swappingVersion = $state<string | null>(null)
	let statusMsg = $state<string>('')
	let javaOverride = $state('')
	let javaSaved = $state(false)

	let showReleases = $state(true)
	let showPreReleases = $state(false)
	let showSnapshots = $state(false)

	const filteredVersions = $derived(
		versions.filter((v) => {
			if (v.isActive) return true
			if (v.category === 'release' && showReleases) return true
			if (v.category === 'pre_release' && showPreReleases) return true
			if (v.category === 'snapshot' && showSnapshots) return true
			return false
		})
	)

	onMount(async () => {
		try {
			const [s, v] = await Promise.all([GetSettings(), GetAvailableVersions()])
			settings = s
			versions = v
			javaOverride = s.javaPathOverride || ''
		} catch (e) {
			statusMsg = (e as Error).message
		} finally {
			loading = false
		}
	})

	async function refreshVersions() {
		try {
			versions = await GetAvailableVersions()
		} catch (e) {
			console.error('Failed to refresh versions', e)
		}
	}

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

	async function handleSwapVersion(targetVer: string) {
		if (swappingVersion) return
		swappingVersion = targetVer
		statusMsg = `Preparing Minecraft ${targetVer}...`
		try {
			await SwapServerVersion(targetVer)
			settings = await GetSettings()
			await refreshVersions()
			statusMsg = `Successfully updated to Minecraft ${targetVer}`
		} catch (e) {
			statusMsg = `Error: ${(e as Error).message}`
		} finally {
			swappingVersion = null
		}
	}

	async function saveJavaOverride() {
		if (!settings) return
		settings.javaPathOverride = javaOverride.trim()
		saving = true
		try {
			await SaveSettings(settings)
			javaSaved = true
			setTimeout(() => (javaSaved = false), 2500)
		} finally {
			saving = false
		}
	}

	async function handleOpenInstanceFolder() {
		await OpenInstanceFolder()
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
			<div class="modal-title"><Icon name="sliders" size={16} />Settings</div>
			<button class="icon-btn" aria-label="Close" onclick={onClose}><Icon name="close" size={15} /></button>
		</div>

		<div class="modal-tabs">
			<button class="tab-btn" class:active={activeTab === 'server'} onclick={() => (activeTab = 'server')}>
				<Icon name="layers" size={14} /> Server & Versions
			</button>
			<button class="tab-btn" class:active={activeTab === 'startup'} onclick={() => (activeTab = 'startup')}>
				<Icon name="bolt" size={14} /> Startup
			</button>
			<button class="tab-btn" class:active={activeTab === 'system'} onclick={() => (activeTab = 'system')}>
				<Icon name="terminal" size={14} /> System & Java
			</button>
		</div>

		<div class="modal-body">
			{#if loading}
				<div class="empty-state">
					<Icon name="spinner" size={26} class="spin" />
					<div class="empty-copy">Loading settings & versions...</div>
				</div>
			{:else}
				{#if statusMsg}
					<div class="status-banner" class:error={statusMsg.startsWith('Error:')}>
						<Icon name={statusMsg.startsWith('Error:') ? 'alert' : 'info'} size={14} />
						<span>{statusMsg}</span>
					</div>
				{/if}

				{#if activeTab === 'server'}
					<div class="section-intro">
						<div class="eyebrow">Minecraft Target Version</div>
						<p class="hint-text">
							Select the Minecraft version for your headless server instance. The mod backend currently targets <strong
								>Fabric-26.2</strong
							>.
						</p>
					</div>

					<div class="version-filter-bar">
						<span class="filter-label">Show:</span>
						<label class="filter-chip" class:active={showReleases}>
							<input type="checkbox" bind:checked={showReleases} />
							<span>Stable Releases</span>
						</label>
						<label class="filter-chip" class:active={showPreReleases}>
							<input type="checkbox" bind:checked={showPreReleases} />
							<span>Pre-releases</span>
						</label>
						<label class="filter-chip" class:active={showSnapshots}>
							<input type="checkbox" bind:checked={showSnapshots} />
							<span>Snapshots</span>
						</label>
					</div>

					<div class="version-list">
						{#each filteredVersions as v (v.gameVersion)}
							<div class="version-card" class:active={v.isActive}>
								<div class="version-info">
									<div class="version-title">
										<span class="version-name">Minecraft {v.gameVersion}</span>
										{#if v.isActive}
											<span class="badge active">Active</span>
										{/if}
										{#if v.supportedByMod}
											<span class="badge target">Mod Target</span>
										{:else if v.category === 'release'}
											<span class="badge stable">Stable</span>
										{:else if v.category === 'pre_release'}
											<span class="badge prerelease">Pre-release</span>
										{:else if v.category === 'snapshot'}
											<span class="badge snapshot">Snapshot</span>
										{/if}
										{#if v.isDownloaded}
											<span class="badge downloaded">Cached</span>
										{/if}
									</div>
									<div class="version-sub">
										{#if v.supportedByMod}
											Official Tree Engine mod backend build (Fabric 26.2)
										{:else}
											Fabric server for Minecraft {v.gameVersion}
										{/if}
									</div>
								</div>

								<div class="version-action">
									{#if v.isActive}
										<button class="btn secondary" disabled>
											<Icon name="check" size={13} /> Active
										</button>
									{:else if swappingVersion === v.gameVersion}
										<button class="btn primary" disabled>
											<Icon name="spinner" size={13} class="spin" /> Swapping...
										</button>
									{:else if v.isDownloaded}
										<button
											class="btn primary"
											disabled={swappingVersion !== null}
											onclick={() => handleSwapVersion(v.gameVersion)}
										>
											Swap to {v.gameVersion}
										</button>
									{:else}
										<button
											class="btn primary-accent"
											disabled={swappingVersion !== null}
											onclick={() => handleSwapVersion(v.gameVersion)}
										>
											Download & Swap
										</button>
									{/if}
								</div>
							</div>
						{/each}
					</div>

					<div class="info-card">
						<Icon name="info" size={15} />
						<div class="info-card-text">
							Server jars are cached in your local AppData directory (<code class="code-inline"
								>%LOCALAPPDATA%\TreeEngineLauncher\servers</code
							>). Changing target version automatically stops and restarts the server if currently running.
						</div>
					</div>
				{:else if activeTab === 'startup'}
					<div class="eyebrow">Launch Preferences</div>
					<label class="setting-row" class:on={settings?.autoStartOnLaunch}>
						<div class="setting-text">
							<div class="setting-label">Auto-start Minecraft server on launch</div>
							<div class="setting-hint">Automatically boot the managed server when Tree Engine starts.</div>
						</div>
						<input
							type="checkbox"
							checked={settings?.autoStartOnLaunch}
							disabled={saving}
							onchange={toggleAutoStart}
						/>
					</label>

					{#if settings?.recentProjects && settings.recentProjects.length > 0}
						<div class="eyebrow mt-16">Recent Projects</div>
						<div class="recent-projects-list">
							{#each settings.recentProjects as proj}
								<div class="recent-proj-item">
									<Icon name="folder" size={14} />
									<span class="proj-path">{proj}</span>
								</div>
							{/each}
						</div>
					{/if}
				{:else if activeTab === 'system'}
					<div class="eyebrow">Java Runtime Override</div>
					<div class="setting-block">
						<div class="setting-hint mb-8">
							Specify a custom Java executable (<code class="code-inline">java.exe</code>) path. If left blank, Tree Engine uses system Java or the bundled Temurin JRE.
						</div>
						<div class="input-row">
							<input
								type="text"
								class="text-input"
								placeholder="Default (Auto-detected / Bundled JRE)"
								bind:value={javaOverride}
							/>
							<button class="btn secondary" disabled={saving} onclick={saveJavaOverride}>
								{#if javaSaved}
									<Icon name="check" size={14} /> Saved
								{:else}
									Save
								{/if}
							</button>
						</div>
					</div>

					<div class="eyebrow mt-16">Server Instance Data</div>
					<div class="setting-block flex-between">
						<div>
							<div class="setting-label">Launcher Data Directory</div>
							<div class="setting-hint">Open the local AppData folder containing server configs and jars.</div>
						</div>
						<button class="btn secondary" onclick={handleOpenInstanceFolder}>
							<Icon name="folder" size={14} /> Open Folder
						</button>
					</div>
				{/if}
			{/if}
		</div>

		<div class="modal-foot">
			<button class="btn secondary" onclick={onClose}>Close</button>
		</div>
	</div>
</div>

<style>
	.settings-modal {
		width: min(560px, 94vw);
		max-height: 85vh;
		display: flex;
		flex-direction: column;
	}

	.modal-tabs {
		display: flex;
		border-bottom: 1px solid var(--line);
		padding: 0 16px;
		gap: 8px;
		background: var(--bg-sunken);
	}

	.tab-btn {
		display: flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: none;
		border-bottom: 2px solid transparent;
		color: var(--text-faint);
		padding: 10px 12px;
		font-size: 12.5px;
		font-weight: 500;
		cursor: pointer;
		transition: color var(--fast), border-color var(--fast);
	}

	.tab-btn:hover {
		color: var(--text);
	}

	.tab-btn.active {
		color: var(--accent);
		border-bottom-color: var(--accent);
		font-weight: 600;
	}

	.modal-body {
		flex: 1;
		overflow-y: auto;
		padding: 16px;
		display: flex;
		flex-direction: column;
		gap: 12px;
	}

	.section-intro {
		margin-bottom: 4px;
	}

	.hint-text {
		font-size: 11.5px;
		color: var(--text-faint);
		margin-top: 2px;
		line-height: 1.4;
	}

	.version-filter-bar {
		display: flex;
		align-items: center;
		gap: 8px;
		margin-top: 4px;
		margin-bottom: 2px;
		flex-wrap: wrap;
	}

	.filter-label {
		font-size: 11px;
		font-weight: 600;
		color: var(--text-faint);
		text-transform: uppercase;
		letter-spacing: 0.4px;
	}

	.filter-chip {
		display: inline-flex;
		align-items: center;
		gap: 6px;
		padding: 4px 10px;
		border-radius: 20px;
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		font-size: 11.5px;
		color: var(--text-faint);
		cursor: pointer;
		user-select: none;
		transition: all var(--fast);
	}

	.filter-chip:hover {
		border-color: var(--line-strong);
		color: var(--text);
	}

	.filter-chip.active {
		background: var(--accent-wash);
		border-color: var(--accent-line);
		color: var(--accent);
		font-weight: 550;
	}

	.filter-chip input {
		accent-color: var(--accent);
		cursor: pointer;
		margin: 0;
		width: 13px;
		height: 13px;
	}

	.version-list {
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.version-card {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 10px 14px;
		transition: border-color var(--fast), background var(--fast);
	}

	.version-card:hover {
		border-color: var(--line-strong);
	}

	.version-card.active {
		border-color: var(--accent-line);
		background: linear-gradient(180deg, var(--accent-wash), transparent), var(--bg-sunken);
	}

	.version-info {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.version-title {
		display: flex;
		align-items: center;
		gap: 6px;
		flex-wrap: wrap;
	}

	.version-name {
		font-size: 13px;
		font-weight: 600;
		color: var(--text);
	}

	.badge {
		font-size: 10px;
		font-weight: 600;
		padding: 2px 6px;
		border-radius: 4px;
		text-transform: uppercase;
		letter-spacing: 0.3px;
	}

	.badge.active {
		background: var(--accent-wash);
		color: var(--accent);
		border: 1px solid var(--accent-line);
	}

	.badge.target {
		background: rgba(46, 204, 113, 0.15);
		color: #2ecc71;
		border: 1px solid rgba(46, 204, 113, 0.3);
	}

	.badge.stable {
		background: rgba(46, 204, 113, 0.12);
		color: #2ecc71;
		border: 1px solid rgba(46, 204, 113, 0.25);
	}

	.badge.prerelease {
		background: rgba(241, 196, 15, 0.15);
		color: #f1c40f;
		border: 1px solid rgba(241, 196, 15, 0.3);
	}

	.badge.snapshot {
		background: rgba(155, 89, 182, 0.15);
		color: #9b59b6;
		border: 1px solid rgba(155, 89, 182, 0.3);
	}

	.badge.downloaded {
		background: rgba(52, 152, 219, 0.15);
		color: #3498db;
		border: 1px solid rgba(52, 152, 219, 0.3);
	}

	.version-sub {
		font-size: 11px;
		color: var(--text-faint);
	}

	.version-action {
		flex-shrink: 0;
	}

	.btn.primary-accent {
		background: var(--accent);
		color: #fff;
		border: none;
		padding: 6px 12px;
		border-radius: var(--r-sm);
		font-size: 12px;
		font-weight: 550;
		cursor: pointer;
	}

	.btn.primary-accent:hover:not(:disabled) {
		filter: brightness(1.1);
	}

	.status-banner {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 8px 12px;
		border-radius: var(--r-sm);
		background: var(--accent-wash);
		border: 1px solid var(--accent-line);
		color: var(--text);
		font-size: 12px;
	}

	.status-banner.error {
		background: rgba(231, 76, 60, 0.15);
		border-color: rgba(231, 76, 60, 0.3);
		color: #e74c3c;
	}

	.info-card {
		display: flex;
		align-items: flex-start;
		gap: 10px;
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		padding: 10px 12px;
		border-radius: var(--r-md);
		font-size: 11.5px;
		color: var(--text-faint);
		line-height: 1.45;
	}

	.code-inline {
		font-family: monospace;
		background: rgba(255, 255, 255, 0.06);
		padding: 1px 4px;
		border-radius: 3px;
		color: var(--text);
	}

	.setting-row {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 16px;
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 12px 14px;
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

	.setting-block {
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 12px 14px;
	}

	.flex-between {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
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
		line-height: 1.4;
	}

	.input-row {
		display: flex;
		gap: 8px;
	}

	.text-input {
		flex: 1;
		background: var(--bg-body);
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		color: var(--text);
		padding: 6px 10px;
		font-size: 12px;
	}

	.text-input:focus {
		border-color: var(--accent);
		outline: none;
	}

	.recent-projects-list {
		display: flex;
		flex-direction: column;
		gap: 4px;
	}

	.recent-proj-item {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 6px 10px;
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		font-size: 11.5px;
		color: var(--text-faint);
	}

	.proj-path {
		overflow: hidden;
		text-overflow: ellipsis;
		white-space: nowrap;
	}

	.mt-16 {
		margin-top: 16px;
	}
	.mb-8 {
		margin-bottom: 8px;
	}
</style>
