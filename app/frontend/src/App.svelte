<script lang="ts">
	import { onMount } from 'svelte'
	import {
		CloseProject,
		GetCurrentProject,
		GetRecentProjects,
		GetSettings,
		GetStatus,
		OpenInstanceFolder,
		OpenProjectFolder,
		OpenRecentProject,
		RunSetup,
		StartServer,
		StopServer,
	} from '../wailsjs/go/main/App'
	import { EventsOn } from '../wailsjs/runtime/runtime'
	import Icon from './lib/Icon.svelte'
	import SettingsModal from './lib/SettingsModal.svelte'
	import TreeEditor from './lib/TreeEditor.svelte'

	type Phase = 'needs_setup' | 'setting_up' | 'needs_project' | 'stopped' | 'starting' | 'running' | 'error'

	const PHASE_LABEL: Record<Phase, string> = {
		needs_setup: 'Not set up',
		setting_up: 'Setting up',
		needs_project: 'No project open',
		stopped: 'Stopped',
		starting: 'Starting',
		running: 'Running',
		error: 'Error',
	}

	type ProjectInfo = { path: string; name: string }
	const EMPTY_PROJECT: ProjectInfo = { path: '', name: '' }

	let phase = $state<Phase>('needs_setup')
	let message = $state('')
	let port = $state(0)
	let token = $state('')
	let eulaAgreed = $state(false)
	let settingUp = $state(false)
	let logLines = $state<string[]>([])
	let consoleOpen = $state(false)
	let consoleAutoOpened = false
	let settingsOpen = $state(false)
	let logPaneEl = $state<HTMLDivElement | undefined>(undefined)

	let currentProject = $state<ProjectInfo>(EMPTY_PROJECT)
	let recentProjects = $state<ProjectInfo[]>([])
	let openingProject = $state(false)
	let projectError = $state('')

	const showFirstRun = $derived(phase === 'needs_setup' || phase === 'setting_up')
	const showNeedsProject = $derived(phase === 'needs_project')
	const pillClass = $derived(
		phase === 'running' ? 'running' : phase === 'error' ? 'error' : phase === 'starting' ? 'starting' : 'stopped',
	)
	const canStart = $derived(phase === 'stopped')
	const canStop = $derived(phase === 'starting' || phase === 'running')

	async function refreshProject(): Promise<void> {
		const [current, recents] = await Promise.all([GetCurrentProject(), GetRecentProjects()])
		currentProject = current
		recentProjects = recents
	}

	onMount(() => {
		EventsOn('status', (payload: any) => {
			phase = payload.phase
			message = payload.message
			port = payload.port
			token = payload.token
			if (payload.phase !== 'setting_up') settingUp = false
			// Auto-close the console we auto-opened once the auto-started server
			// has settled (successfully running, failed, or exited early) - but
			// never touch a console the user opened/closed themselves.
			if (consoleAutoOpened && (payload.phase === 'running' || payload.phase === 'error' || payload.phase === 'stopped')) {
				consoleOpen = false
				consoleAutoOpened = false
			}
			// Project state can change server-side (switch, close) independent of
			// any click here - keep the topbar/gate screen in sync whenever the
			// server tells us something happened.
			void refreshProject()
		})
		EventsOn('server:log', (line: string) => {
			logLines = [...logLines, line]
			if (logLines.length > 500) logLines = logLines.slice(logLines.length - 500)
			queueMicrotask(() => {
				if (logPaneEl) logPaneEl.scrollTop = logPaneEl.scrollHeight
			})
		})
		Promise.all([GetStatus(), GetSettings()]).then(([status, settings]) => {
			phase = status.phase as Phase
			message = status.message
			port = status.port
			token = status.token
			if (settings.autoStartOnLaunch && (phase === 'starting' || phase === 'setting_up')) {
				consoleOpen = true
				consoleAutoOpened = true
			}
		})
		void refreshProject()
	})

	function startSetup() {
		settingUp = true
		RunSetup(true)
	}

	async function openProjectFolder(): Promise<void> {
		openingProject = true
		projectError = ''
		try {
			const info = await OpenProjectFolder()
			if (info.path) currentProject = info
		} catch (e) {
			projectError = (e as Error).message
		} finally {
			openingProject = false
			await refreshProject()
		}
	}

	async function openRecentProject(path: string): Promise<void> {
		openingProject = true
		projectError = ''
		try {
			const info = await OpenRecentProject(path)
			if (info.path) currentProject = info
		} catch (e) {
			projectError = (e as Error).message
		} finally {
			openingProject = false
			await refreshProject()
		}
	}

	async function closeProject(): Promise<void> {
		await CloseProject()
		await refreshProject()
	}
</script>

{#if showFirstRun}
	<div class="screen firstrun">
		<div class="firstrun-card">
			<header class="firstrun-head">
				<div class="mark"><Icon name="tree" size={26} /></div>
				<div>
					<h1>Tree Engine</h1>
					<p class="firstrun-tagline">Design, preview and ship custom Minecraft trees.</p>
				</div>
			</header>

			<div class="setup-steps">
				<div class="eyebrow">First-run setup</div>
				<ul>
					<li><span class="step-dot"></span>Download a Java runtime, if you don't have one</li>
					<li><span class="step-dot"></span>Install a Fabric server + Fabric API</li>
					<li><span class="step-dot"></span>Install the Tree Engine mod</li>
				</ul>
			</div>

			<div class="eula-wrap">
				<div class="eula-box">
					Before a Minecraft server can run, you must agree to the Minecraft End User License Agreement and Privacy
					Policy, published by Mojang / Microsoft at <strong class="mono">https://aka.ms/MinecraftEULA</strong>. Tree
					Engine downloads and runs an unmodified vanilla Minecraft server (via Fabric) on your machine; this app does
					not host, redistribute, or modify any Mojang software.
				</div>
			</div>

			<label class="eula-agree" class:checked={eulaAgreed}>
				<input type="checkbox" bind:checked={eulaAgreed} disabled={settingUp} />
				<span>I have read and agree to the Minecraft EULA</span>
			</label>

			<button class="btn btn-lg full" disabled={!eulaAgreed || settingUp} onclick={startSetup}>
				{#if settingUp}
					<Icon name="spinner" size={16} class="spin" />
					Setting up...
				{:else}
					<Icon name="play" size={15} />
					Set Up &amp; Start
				{/if}
			</button>

			{#if settingUp || message}
				<div class="progress-log">
					<span class="progress-caret">&gt;</span>
					<span>{message || 'Waiting...'}</span>
				</div>
			{/if}
			{#if phase === 'error'}
				<div class="callout error"><Icon name="alert" size={15} /><span>{message}</span></div>
			{/if}
		</div>
	</div>
{:else if showNeedsProject}
	<div class="screen firstrun">
		<div class="firstrun-card">
			<header class="firstrun-head">
				<div class="mark"><Icon name="folder" size={26} /></div>
				<div>
					<h1>Open a Project</h1>
					<p class="firstrun-tagline">Pick a folder to work in - it becomes your live datapack directly, no copying.</p>
				</div>
			</header>

			<button class="btn btn-lg full" disabled={openingProject} onclick={openProjectFolder}>
				{#if openingProject}
					<Icon name="spinner" size={16} class="spin" />
					Opening...
				{:else}
					<Icon name="folder" size={15} />
					Open Folder...
				{/if}
			</button>

			{#if recentProjects.length > 0}
				<div class="setup-steps">
					<div class="eyebrow">Recent Projects</div>
					<ul class="recent-list">
						{#each recentProjects as p (p.path)}
							<li>
								<button class="recent-item" disabled={openingProject} onclick={() => openRecentProject(p.path)}>
									<Icon name="folder" size={14} />
									<span class="recent-item-text">
										<span class="recent-item-name">{p.name}</span>
										<span class="recent-item-path mono">{p.path}</span>
									</span>
								</button>
							</li>
						{/each}
					</ul>
				</div>
			{/if}

			<div class="eula-wrap">
				<div class="eula-box">
					Already made trees before this update? They're still on disk under
					<strong class="mono">...\TreeEngineLauncher\instance\config\tree_engine\datapacks\tree_engine_trees</strong>
					- open that folder to pick up where you left off.
				</div>
			</div>

			{#if projectError}
				<div class="callout error"><Icon name="alert" size={15} /><span>{projectError}</span></div>
			{/if}
		</div>
	</div>
{:else}
	<div class="control-panel">
		<header class="topbar">
			<div class="topbar-left">
				<div class="brand">
					<span class="brand-mark"><Icon name="tree" size={17} /></span>
					<span class="brand-name">TREE<span class="brand-name-alt">ENGINE</span></span>
				</div>
				<span class="bar-sep"></span>
				{#if currentProject.path}
					<button
						class="project-chip"
						title="Switch project ({currentProject.path})"
						disabled={openingProject}
						onclick={openProjectFolder}
					>
						<Icon name="folder" size={13} />
						<span class="project-chip-name">{currentProject.name}</span>
					</button>
					<span class="bar-sep"></span>
				{/if}
				<div class="status-pill {pillClass}"><span class="dot"></span>{PHASE_LABEL[phase] ?? phase}</div>
				{#if phase === 'running' && port}
					<span class="port-chip mono">:{port}</span>
				{/if}
				{#if message}
					<span class="topbar-message">{message}</span>
				{/if}
			</div>
			<div class="topbar-right">
				{#if canStop}
					<button class="btn secondary btn-sm" onclick={() => StopServer()}>
						<Icon name="stop" size={13} />Stop
					</button>
				{:else}
					<button class="btn btn-sm" disabled={!canStart} onclick={() => StartServer()}>
						<Icon name="play" size={13} />Start Server
					</button>
				{/if}
				<span class="bar-sep"></span>
				<button class="icon-btn" title="Server console" aria-label="Server console" onclick={() => (consoleOpen = true)}>
					<Icon name="terminal" size={16} />
				</button>
				<button
					class="icon-btn"
					title="Open instance folder"
					aria-label="Open instance folder"
					onclick={() => OpenInstanceFolder()}>
					<Icon name="folder" size={16} />
				</button>
				<button class="icon-btn" title="Settings" aria-label="Settings" onclick={() => (settingsOpen = true)}>
					<Icon name="sliders" size={16} />
				</button>
				<button class="icon-btn" title="Close project" aria-label="Close project" onclick={closeProject}>
					<Icon name="close" size={16} />
				</button>
			</div>
		</header>
		<div class="content-pane">
			{#if phase === 'running' && port}
				<TreeEditor conn={{ port, token }} />
			{:else}
				<div class="content-pane-placeholder">
					<div class="empty-state">
						<Icon name="tree" size={40} />
						<div class="empty-title">
							{phase === 'starting' ? 'Booting the server' : 'Editor offline'}
						</div>
						<div class="empty-copy">
							{phase === 'starting'
								? 'The Minecraft server is starting up. The tree editor opens automatically once it is ready.'
								: 'Start the server to browse your tree library and open the live preview.'}
						</div>
						{#if phase !== 'starting' && canStart}
							<button class="btn btn-sm" onclick={() => StartServer()}><Icon name="play" size={13} />Start Server</button>
						{/if}
					</div>
				</div>
			{/if}
		</div>
	</div>

	{#if consoleOpen}
		<div
			class="modal-backdrop"
			role="button"
			tabindex="0"
			onclick={(e) => {
				if (e.target === e.currentTarget) consoleOpen = false
			}}
			onkeydown={(e) => {
				if (e.key === 'Enter' || e.key === ' ') consoleOpen = false
			}}
		>
			<div class="modal console-modal">
				<div class="modal-head">
					<div class="modal-title"><Icon name="terminal" size={15} />Server Console</div>
					<div class="console-head-right">
						<span class="count-badge">{logLines.length} lines</span>
						<button class="icon-btn" aria-label="Close console" onclick={() => (consoleOpen = false)}>
							<Icon name="close" size={15} />
						</button>
					</div>
				</div>
				<div class="log-pane" bind:this={logPaneEl}>{logLines.join('\n')}</div>
			</div>
		</div>
	{/if}
{/if}

{#if settingsOpen}
	<SettingsModal onClose={() => (settingsOpen = false)} />
{/if}

<svelte:window
	onkeydown={(e) => {
		if (e.key === 'Escape' && consoleOpen) consoleOpen = false
	}}
/>

<style>
	/* --- Top bar --- */

	.control-panel {
		display: flex;
		flex-direction: column;
		height: 100%;
	}

	.topbar {
		position: relative;
		background: linear-gradient(180deg, #191e1a, var(--panel));
		border-bottom: 1px solid var(--line);
		padding: 0 12px 0 14px;
		height: 54px;
		flex-shrink: 0;
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
		box-shadow: 0 1px 0 rgba(0, 0, 0, 0.45), var(--inset-hi);
		z-index: 5;
	}

	.topbar-left {
		display: flex;
		align-items: center;
		gap: 10px;
		min-width: 0;
	}

	.topbar-right {
		display: flex;
		align-items: center;
		gap: 4px;
		flex-shrink: 0;
	}

	.topbar-right .btn {
		margin-right: 4px;
	}

	.bar-sep {
		width: 1px;
		height: 20px;
		background: var(--line);
		flex-shrink: 0;
		margin: 0 2px;
	}

	.brand {
		display: flex;
		align-items: center;
		gap: 9px;
		white-space: nowrap;
	}

	.brand-mark {
		display: grid;
		place-items: center;
		width: 28px;
		height: 28px;
		border-radius: var(--r-sm);
		color: var(--accent);
		background: var(--accent-wash);
		border: 1px solid var(--accent-line);
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.06);
	}

	.brand-name {
		font-family: var(--font-display);
		font-size: 12.5px;
		font-weight: 700;
		letter-spacing: 0.16em;
		color: var(--text);
	}

	.brand-name-alt {
		color: var(--text-faint);
		font-weight: 600;
		margin-left: 0.16em;
	}

	.port-chip {
		color: var(--accent);
		background: var(--accent-wash);
		border: 1px solid var(--accent-line);
		border-radius: var(--r-xs);
		padding: 2px 6px;
		font-size: 11px;
		flex-shrink: 0;
	}

	.project-chip {
		display: flex;
		align-items: center;
		gap: 6px;
		background: transparent;
		border: 1px solid transparent;
		border-radius: var(--r-sm);
		padding: 5px 8px;
		color: var(--text-dim);
		font-family: var(--font-sans);
		font-size: 12px;
		font-weight: 550;
		cursor: pointer;
		min-width: 0;
		transition: background var(--fast), border-color var(--fast), color var(--fast);
	}

	.project-chip:hover:not(:disabled) {
		background: var(--raised);
		border-color: var(--line);
		color: var(--text);
	}

	.project-chip:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.project-chip-name {
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
		max-width: 180px;
	}

	.topbar-message {
		font-size: 12px;
		color: var(--text-faint);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.content-pane {
		flex: 1;
		min-height: 0;
		display: flex;
		background: var(--bg);
	}

	.content-pane-placeholder {
		margin: auto;
	}

	/* --- Console --- */

	.console-modal {
		width: min(940px, 92vw);
		height: min(620px, 82vh);
	}

	.console-head-right {
		display: flex;
		align-items: center;
		gap: 10px;
	}

	/* --- First run --- */

	.firstrun {
		height: 100%;
		overflow-y: auto;
		display: flex;
		align-items: flex-start;
		justify-content: center;
		padding: 40px 24px;
	}

	.firstrun-card {
		width: min(600px, 100%);
		background: linear-gradient(180deg, rgba(255, 255, 255, 0.02), transparent 120px), var(--panel);
		border: 1px solid var(--line);
		border-radius: var(--r-lg);
		box-shadow: var(--shadow-md);
		padding: 26px;
		display: flex;
		flex-direction: column;
		gap: 18px;
	}

	.firstrun-head {
		display: flex;
		align-items: center;
		gap: 14px;
	}

	.mark {
		display: grid;
		place-items: center;
		width: 46px;
		height: 46px;
		flex-shrink: 0;
		border-radius: var(--r-md);
		color: var(--accent);
		background: var(--accent-wash);
		border: 1px solid var(--accent-line);
		box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.07), 0 0 24px -8px rgba(139, 197, 63, 0.5);
	}

	.firstrun-head h1 {
		font-size: 21px;
		letter-spacing: -0.015em;
	}

	.firstrun-tagline {
		margin: 3px 0 0;
		color: var(--text-dim);
		font-size: 12.5px;
	}

	.setup-steps ul {
		list-style: none;
		margin: 10px 0 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}

	.setup-steps li {
		display: flex;
		align-items: center;
		gap: 10px;
		font-size: 12.5px;
		color: var(--text-dim);
	}

	.step-dot {
		width: 5px;
		height: 5px;
		border-radius: 50%;
		background: var(--accent);
		box-shadow: 0 0 0 3px var(--accent-wash);
		flex-shrink: 0;
	}

	.recent-list {
		list-style: none;
		margin: 10px 0 0;
		padding: 0;
		display: flex;
		flex-direction: column;
		gap: 6px;
		max-height: 180px;
		overflow-y: auto;
	}

	.recent-item {
		width: 100%;
		display: flex;
		align-items: center;
		gap: 9px;
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-sm);
		padding: 8px 10px;
		color: var(--text-dim);
		cursor: pointer;
		text-align: left;
		transition: border-color var(--fast), background var(--fast), color var(--fast);
	}

	.recent-item:hover:not(:disabled) {
		border-color: var(--accent-line);
		background: var(--accent-wash);
		color: var(--text);
	}

	.recent-item:disabled {
		opacity: 0.5;
		cursor: not-allowed;
	}

	.recent-item-text {
		display: flex;
		flex-direction: column;
		gap: 1px;
		min-width: 0;
	}

	.recent-item-name {
		font-size: 12.5px;
		font-weight: 550;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.recent-item-path {
		font-size: 10.5px;
		color: var(--text-faint);
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	/* Fade the scrollable legal text out at its bottom edge instead of clipping. */
	.eula-wrap {
		position: relative;
	}

	.eula-wrap::after {
		content: '';
		position: absolute;
		left: 1px;
		right: 1px;
		bottom: 1px;
		height: 34px;
		border-radius: 0 0 var(--r-md) var(--r-md);
		background: linear-gradient(transparent, var(--bg-sunken));
		pointer-events: none;
	}

	.eula-box {
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 14px 16px 22px;
		max-height: 190px;
		overflow-y: auto;
		font-size: 12px;
		color: var(--text-dim);
		line-height: 1.6;
	}

	.eula-box strong {
		color: var(--accent);
		font-weight: 500;
	}

	.eula-agree {
		display: flex;
		align-items: center;
		gap: 11px;
		font-size: 12.5px;
		color: var(--text-dim);
		background: var(--raised);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 12px 14px;
		cursor: pointer;
		transition: border-color var(--fast), color var(--fast), background var(--fast);
	}

	.eula-agree:hover {
		border-color: var(--line-strong);
		color: var(--text);
	}

	.eula-agree.checked {
		border-color: var(--accent-line);
		background: var(--accent-wash);
		color: var(--text);
	}

	.progress-log {
		display: flex;
		gap: 9px;
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 12px 14px;
		font-family: var(--font-mono);
		font-size: 11.5px;
		line-height: 1.5;
		color: var(--text-dim);
		word-break: break-word;
	}

	.progress-caret {
		color: var(--accent);
		flex-shrink: 0;
	}

	:global(.spin) {
		animation: spin 900ms linear infinite;
	}

	@keyframes spin {
		to {
			transform: rotate(360deg);
		}
	}
</style>
