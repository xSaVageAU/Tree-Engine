<script lang="ts">
	import { onMount } from 'svelte'
	import { GetSettings, GetStatus, OpenInstanceFolder, RunSetup, StartServer, StopServer } from '../wailsjs/go/main/App'
	import { EventsOn } from '../wailsjs/runtime/runtime'
	import SettingsModal from './lib/SettingsModal.svelte'
	import TreeEditor from './lib/TreeEditor.svelte'

	type Phase = 'needs_setup' | 'setting_up' | 'stopped' | 'starting' | 'running' | 'error'

	const PHASE_LABEL: Record<Phase, string> = {
		needs_setup: 'Not set up',
		setting_up: 'Setting up',
		stopped: 'Stopped',
		starting: 'Starting',
		running: 'Running',
		error: 'Error',
	}

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

	const showFirstRun = $derived(phase === 'needs_setup' || phase === 'setting_up')
	const pillClass = $derived(
		phase === 'running' ? 'running' : phase === 'error' ? 'error' : phase === 'starting' ? 'starting' : 'stopped',
	)
	const canStart = $derived(phase === 'stopped')
	const canStop = $derived(phase === 'starting' || phase === 'running')

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
	})

	function startSetup() {
		settingUp = true
		RunSetup(true)
	}
</script>

{#if showFirstRun}
	<div class="screen firstrun">
		<h1>Tree Engine</h1>
		<div class="subtitle">
			First-run setup will download Java (if needed), a Fabric server, Fabric API, and install the Tree Engine mod
			automatically.
		</div>

		<div class="eula-box">
			Before a Minecraft server can run, you must agree to the Minecraft End User License Agreement and Privacy
			Policy, published by Mojang / Microsoft at <strong>https://aka.ms/MinecraftEULA</strong>. Tree Engine downloads
			and runs an unmodified vanilla Minecraft server (via Fabric) on your machine; this app does not host,
			redistribute, or modify any Mojang software.
		</div>

		<label class="eula-agree">
			<input type="checkbox" bind:checked={eulaAgreed} disabled={settingUp} />
			I have read and agree to the Minecraft EULA
		</label>

		<button class="btn" disabled={!eulaAgreed || settingUp} onclick={startSetup}>
			{settingUp ? 'Setting up...' : 'Set Up & Start'}
		</button>

		{#if settingUp || message}
			<div class="progress-log">{message}</div>
		{/if}
		{#if phase === 'error'}
			<div class="error-box">{message}</div>
		{/if}
	</div>
{:else}
	<div class="control-panel">
		<div class="topbar">
			<div class="topbar-left">
				<span class="brand">Tree Engine</span>
				<div class="status-pill {pillClass}"><span class="dot"></span>{PHASE_LABEL[phase] ?? phase}</div>
				{#if message}
					<span class="topbar-message">{message}</span>
				{/if}
			</div>
			<div class="topbar-right">
				<button class="btn btn-sm" disabled={!canStart} onclick={() => StartServer()}>Start Server</button>
				<button class="btn btn-sm secondary" disabled={!canStop} onclick={() => StopServer()}>Stop Server</button>
				<button class="btn btn-sm secondary" onclick={() => (consoleOpen = true)}>Console</button>
				<button class="btn btn-sm secondary" onclick={() => OpenInstanceFolder()}>Open Instance Folder</button>
				<button class="btn btn-sm secondary" onclick={() => (settingsOpen = true)}>⚙ Settings</button>
			</div>
		</div>
		<div class="content-pane">
			{#if phase === 'running' && port}
				<TreeEditor conn={{ port, token }} />
			{:else}
				<div class="content-pane-placeholder">
					{phase === 'starting' ? 'Server is starting...' : 'Start the server to open the editor.'}
				</div>
			{/if}
		</div>
	</div>

	{#if consoleOpen}
		<div
			class="console-modal-backdrop"
			role="button"
			tabindex="0"
			onclick={(e) => {
				if (e.target === e.currentTarget) consoleOpen = false
			}}
			onkeydown={(e) => {
				if (e.key === 'Enter' || e.key === ' ') consoleOpen = false
			}}
		>
			<div class="console-modal">
				<div class="console-modal-header">
					<span>Server Console</span>
					<button class="console-modal-close" onclick={() => (consoleOpen = false)}>&times;</button>
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
