<script lang="ts">
	// Tree browser + editor screen: replaces the old iframe into the mod's
	// Babylon-based web UI. Browsing/CRUD talk to the mod's existing /api/trees
	// routes (unchanged); the live preview is the new deepslate renderer instead
	// of the old per-block-type renderer.
	import { onMount, tick } from 'svelte'
	import { slide } from 'svelte/transition'
	import { cubicOut } from 'svelte/easing'
	import { EnsureAssets } from '../../wailsjs/go/main/App'
	import {
		deleteTree,
		GenerateError,
		generateTree,
		getPlacement,
		getTree,
		getVanillaTree,
		hotReload,
		listTrees,
		savePlacement,
		saveTree,
		type ModConnection,
	} from '../renderer/mod-client'
	import { BIOME_COLORS, DEFAULT_BIOME } from '../renderer/biome-colors'
	import { TreePreview } from '../renderer/preview'
	import type { ApiBlock } from '../renderer/structure'
	import BenchmarkModal from './BenchmarkModal.svelte'
	import ImportModal from './ImportModal.svelte'
	import MonacoEditor from './MonacoEditor.svelte'
	import ReplacersPanel from './ReplacersPanel.svelte'

	let { conn }: { conn: ModConnection } = $props()

	function defaultTreeConfig() {
		return {
			type: 'minecraft:tree',
			config: {
				trunk_provider: { type: 'minecraft:simple_state_provider', state: { Name: 'minecraft:oak_log' } },
				dirt_provider: { type: 'minecraft:simple_state_provider', state: { Name: 'minecraft:dirt' } },
				foliage_provider: { type: 'minecraft:simple_state_provider', state: { Name: 'minecraft:oak_leaves' } },
				trunk_placer: { type: 'minecraft:straight_trunk_placer', base_height: 4, height_rand_a: 2, height_rand_b: 0 },
				foliage_placer: { type: 'minecraft:blob_foliage_placer', radius: 2, offset: 0, height: 3 },
				minimum_size: { type: 'minecraft:two_layers_feature_size', limit: 1, lower_size: 0, upper_size: 1 },
				decorators: [],
			},
		}
	}

	type Screen = 'library' | 'settings' | 'replacers'

	let screen = $state<Screen>('library')
	let trees = $state<string[]>([])
	let searchQuery = $state('')
	let selectedTreeId = $state<string | null>(null)
	let treeName = $state('')

	let currentTreeJson = $state<any>(null)
	let currentPlacementJson = $state<any>(null)

	let jsonPanelOpen = $state(false)
	let jsonTab = $state<'tree' | 'placement'>('tree')
	let jsonPanelHeight = $state(380)
	let editorRootEl = $state<HTMLDivElement | undefined>(undefined)

	function startPanelResize(e: PointerEvent): void {
		e.preventDefault()
		const startY = e.clientY
		const startHeight = jsonPanelHeight
		const containerHeight = editorRootEl?.clientHeight ?? window.innerHeight
		const minHeight = 150
		const maxHeight = Math.max(minHeight, containerHeight - 120)
		document.body.style.userSelect = 'none'

		function onMove(ev: PointerEvent): void {
			const delta = startY - ev.clientY
			jsonPanelHeight = Math.min(maxHeight, Math.max(minHeight, startHeight + delta))
		}
		function onUp(): void {
			document.body.style.userSelect = ''
			window.removeEventListener('pointermove', onMove)
			window.removeEventListener('pointerup', onUp)
		}
		window.addEventListener('pointermove', onMove)
		window.addEventListener('pointerup', onUp)
	}

	let importModalOpen = $state(false)
	let benchmarkModalOpen = $state(false)

	let statusMessage = $state('Ready')
	let statusIsError = $state(false)
	let statusDetails = $state('')
	let showDetails = $state(false)
	let generating = $state(false)
	let saving = $state(false)

	let assetsBaseURL = $state('')
	let assetsError = $state('')

	let canvasEl = $state<HTMLCanvasElement | undefined>(undefined)
	let preview: TreePreview | undefined

	let biome = $state(DEFAULT_BIOME)
	const biomeOptions = Object.keys(BIOME_COLORS)

	function onBiomeChange(): void {
		preview?.setBiome(biome)
	}

	let autoRotateOn = $state(false)
	let showGridOn = $state(true)
	let bgColor = $state('#0f1622')

	function toggleAutoRotate(): void {
		autoRotateOn = !autoRotateOn
		preview?.setAutoRotate(autoRotateOn)
	}

	function toggleShowGrid(): void {
		showGridOn = !showGridOn
		preview?.setShowGrid(showGridOn)
	}

	const filteredTrees = $derived(trees.filter((t) => t.toLowerCase().includes(searchQuery.toLowerCase())))

	onMount(() => {
		EnsureAssets().then((assets) => {
			if (assets.ready) assetsBaseURL = assets.baseURL
			else assetsError = assets.error
		})
		loadTrees()
	})

	async function loadTrees(): Promise<void> {
		try {
			trees = await listTrees(conn)
		} catch (e) {
			console.error('Failed to load trees', e)
		}
	}

	function setStatus(message: string, isError = false, details = ''): void {
		statusMessage = message
		statusIsError = isError
		statusDetails = details
		showDetails = false
	}

	async function generate(): Promise<void> {
		if (!currentTreeJson || !assetsBaseURL) return
		generating = true
		setStatus('Generating...')
		try {
			let blocks: ApiBlock[] = await generateTree(conn, currentTreeJson)
			blocks = blocks.filter((b) => b.blockState.Name !== 'minecraft:air')

			await tick()
			if (!canvasEl) return
			if (!preview) {
				preview = await TreePreview.create(canvasEl, blocks, assetsBaseURL, { showGrid: showGridOn, biome })
				preview.setAutoRotate(autoRotateOn)
				window.addEventListener('resize', () => preview?.resize(canvasEl!))
			} else {
				await preview.setBlocks(blocks, assetsBaseURL, biome)
			}
			setStatus(`Generated ${blocks.length} blocks.`)
		} catch (e) {
			const err = e as GenerateError
			setStatus(err.message, true, err.details ?? '')
		} finally {
			generating = false
		}
	}

	async function openSettings(): Promise<void> {
		screen = 'settings'
		await tick()
		await generate()
	}

	async function selectTree(id: string): Promise<void> {
		selectedTreeId = id
		try {
			currentTreeJson = await getTree(conn, id)
			treeName = id
			try {
				currentPlacementJson = await getPlacement(conn, id)
			} catch {
				currentPlacementJson = { feature: `tree_engine:${id}`, placement: [] }
			}
			await openSettings()
		} catch (e) {
			alert('Failed to load tree: ' + (e as Error).message)
			selectedTreeId = null
		}
	}

	async function createNewTree(): Promise<void> {
		selectedTreeId = null
		treeName = ''
		currentTreeJson = defaultTreeConfig()
		currentPlacementJson = { feature: 'tree_engine:', placement: [] }
		await openSettings()
	}

	async function importVanillaTree(id: string): Promise<void> {
		importModalOpen = false
		try {
			currentTreeJson = await getVanillaTree(conn, id)
			const name = id.includes(':') ? id.split(':')[1] : id
			treeName = name
			selectedTreeId = null
			currentPlacementJson = { feature: `tree_engine:${name}`, placement: [] }
			await openSettings()
		} catch (e) {
			alert('Failed to import tree: ' + (e as Error).message)
		}
	}

	function hideSettings(): void {
		screen = 'library'
		selectedTreeId = null
		preview = undefined
		if (jsonPanelOpen) jsonPanelOpen = false
	}

	async function saveCurrentTree(): Promise<void> {
		const name = treeName.trim().toLowerCase()
		if (!name) {
			alert('Please enter a tree name')
			return
		}
		if (!/^[a-z0-9_]+$/.test(name)) {
			alert('Tree name can only contain letters, numbers, and underscores.')
			return
		}

		saving = true
		try {
			await saveTree(conn, name, currentTreeJson)
			selectedTreeId = name
			if (currentPlacementJson) {
				currentPlacementJson.feature = `tree_engine:${name}`
				await savePlacement(conn, name, currentPlacementJson).catch((e) => console.error('Failed to save placement:', e))
			}
			await loadTrees()
			try {
				await hotReload(conn)
				setStatus('Tree saved and hot-reloaded!')
			} catch {
				setStatus('Tree saved! (hot reload failed)', true)
			}
		} catch (e) {
			alert('Failed to save tree: ' + (e as Error).message)
		} finally {
			saving = false
		}
	}

	async function deleteSelected(): Promise<void> {
		if (!selectedTreeId) return
		if (!confirm('Are you sure you want to delete this tree?')) return
		try {
			await deleteTree(conn, selectedTreeId)
			await loadTrees()
			hideSettings()
		} catch (e) {
			alert('Error deleting tree: ' + (e as Error).message)
		}
	}

	function onTreeJsonChange(text: string): void {
		try {
			currentTreeJson = JSON.parse(text)
			generate()
		} catch {
			// invalid JSON mid-edit - don't regenerate
		}
	}

	function onPlacementJsonChange(text: string): void {
		try {
			currentPlacementJson = JSON.parse(text)
		} catch {
			// invalid JSON mid-edit
		}
	}
</script>

<div class="tree-editor" bind:this={editorRootEl}>
	{#if screen === 'library'}
		<div class="sidebar">
			<div class="sidebar-actions">
				<button class="btn" onclick={createNewTree}>+ Create New Tree</button>
				<button class="btn secondary" onclick={() => (importModalOpen = true)}>Import Tree</button>
				<button class="btn secondary" onclick={() => (screen = 'replacers')}>Tree Replacers</button>
				<input type="search" placeholder="Search trees..." bind:value={searchQuery} />
			</div>
			<div class="tree-list">
				{#each filteredTrees as id (id)}
					<div class="tree-item" onclick={() => selectTree(id)} onkeydown={(e) => e.key === 'Enter' && selectTree(id)} role="button" tabindex="0">
						<h3>{id}</h3>
						<p>Custom Tree</p>
					</div>
				{:else}
					<div class="empty">No trees yet. Create or import one to get started.</div>
				{/each}
			</div>
		</div>
		<div class="canvas-area">
			<div class="placeholder">Select, create, or import a tree to preview it.</div>
		</div>
	{:else if screen === 'replacers'}
		<ReplacersPanel {conn} customTrees={trees} onBack={() => (screen = 'library')} />
	{:else}
		<div class="sidebar">
			<div class="settings-header">
				<button class="back-btn" onclick={hideSettings}>← Back</button>
				<h2>Tree Settings</h2>
			</div>
			<div class="scroll-content">
				<div class="control-group">
					<label for="tree_name">Tree Name</label>
					<input id="tree_name" type="text" placeholder="my_custom_tree" bind:value={treeName} />
				</div>
				<div class="control-group">
					<label for="biome_select">Preview Biome (Tint)</label>
					<select id="biome_select" bind:value={biome} onchange={onBiomeChange}>
						{#each biomeOptions as b (b)}
							<option value={b}>{b.split('_').map((w) => w[0].toUpperCase() + w.slice(1)).join(' ')}</option>
						{/each}
					</select>
				</div>
				<hr />
				<button class="btn secondary full" onclick={() => (jsonPanelOpen = true)}>📝 Edit JSON</button>
			</div>
			<div class="actions">
				<button class="btn full" disabled={generating} onclick={generate}>
					{generating ? 'Generating...' : 'Regenerate Preview'}
				</button>
				<button class="btn secondary full" onclick={() => (benchmarkModalOpen = true)}>⚡ Run Benchmark</button>
				<div class="action-row">
					<button class="btn" disabled={saving} onclick={saveCurrentTree}>{saving ? 'Saving...' : 'Save Tree'}</button>
					<button class="btn secondary" disabled={!selectedTreeId} onclick={deleteSelected}>Delete</button>
				</div>
			</div>
		</div>
		<div class="canvas-area" style="background: {bgColor}">
			<canvas bind:this={canvasEl}></canvas>
			<div class="viewport-toolbar">
				<button class="btn secondary btn-sm" class:active={autoRotateOn} onclick={toggleAutoRotate}>
					🔄 Auto-Rotate
				</button>
				<button class="btn secondary btn-sm" class:active={showGridOn} onclick={toggleShowGrid}> ▦ Grid </button>
				<label class="bg-color-picker" title="Background color">
					<input type="color" bind:value={bgColor} />
				</label>
			</div>
			{#if assetsError}
				<div class="status error">Asset provisioning failed: {assetsError}</div>
			{:else}
				<div class="status" class:error={statusIsError}>
					{statusMessage}
					{#if statusDetails}
						{#if showDetails}
							<div class="status-details">{statusDetails}</div>
						{:else}
							<button class="details-link" onclick={() => (showDetails = true)}>Show Details</button>
						{/if}
					{/if}
				</div>
			{/if}
		</div>

		{#if jsonPanelOpen}
			<div class="json-panel" style="height: {jsonPanelHeight}px" transition:slide={{ duration: 220, easing: cubicOut }}>
				<div
					class="resize-handle"
					role="separator"
					aria-orientation="horizontal"
					aria-label="Resize JSON panel"
					onpointerdown={startPanelResize}
				></div>
				<div class="json-panel-header">
					<div class="tabs">
						<button class:active={jsonTab === 'tree'} onclick={() => (jsonTab = 'tree')}>Tree Config</button>
						<button class:active={jsonTab === 'placement'} onclick={() => (jsonTab = 'placement')}>Placement Rules</button>
					</div>
					<button class="close-btn" onclick={() => (jsonPanelOpen = false)}>✕</button>
				</div>
				<div class="json-panel-body">
					{#if jsonTab === 'tree'}
						<MonacoEditor value={JSON.stringify(currentTreeJson, null, 2)} onChange={onTreeJsonChange} />
					{:else}
						<MonacoEditor value={JSON.stringify(currentPlacementJson, null, 2)} onChange={onPlacementJsonChange} />
					{/if}
				</div>
			</div>
		{/if}
	{/if}
</div>

{#if importModalOpen}
	<ImportModal {conn} onImport={importVanillaTree} onClose={() => (importModalOpen = false)} />
{/if}

{#if benchmarkModalOpen}
	<BenchmarkModal {conn} feature={currentTreeJson} onClose={() => (benchmarkModalOpen = false)} />
{/if}

<style>
	.tree-editor {
		display: flex;
		height: 100%;
		width: 100%;
		position: relative;
	}
	.sidebar {
		width: 300px;
		flex-shrink: 0;
		display: flex;
		flex-direction: column;
		background: var(--panel);
		border-right: 1px solid var(--panel-border);
	}
	.sidebar-actions {
		padding: 15px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.sidebar-actions input {
		background: var(--bg);
		border: 1px solid var(--panel-border);
		color: var(--text);
		border-radius: 6px;
		padding: 8px 10px;
		font-size: 13px;
	}
	.tree-list {
		flex: 1;
		overflow-y: auto;
		padding: 0 15px 15px;
	}
	.tree-item {
		padding: 10px 12px;
		border-radius: 6px;
		cursor: pointer;
		margin-bottom: 6px;
		border: 1px solid var(--panel-border);
	}
	.tree-item:hover {
		background: var(--bg);
	}
	.tree-item h3 {
		margin: 0 0 2px;
		font-size: 13px;
	}
	.tree-item p {
		margin: 0;
		font-size: 11px;
		color: var(--text-dim);
	}
	.empty {
		padding: 20px;
		text-align: center;
		color: var(--text-dim);
		font-size: 13px;
	}
	.canvas-area {
		flex: 1;
		position: relative;
		background: #0f1622;
	}
	.canvas-area canvas {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		touch-action: none;
	}
	.viewport-toolbar {
		position: absolute;
		top: 12px;
		right: 12px;
		display: flex;
		align-items: center;
		gap: 8px;
		z-index: 1;
	}
	.viewport-toolbar .btn.active {
		background: var(--accent);
		color: var(--bg);
	}
	.bg-color-picker {
		display: flex;
		width: 32px;
		height: 32px;
		border-radius: 6px;
		border: 1px solid var(--panel-border);
		overflow: hidden;
		cursor: pointer;
	}
	.bg-color-picker input[type='color'] {
		width: 40px;
		height: 40px;
		margin: -4px;
		padding: 0;
		border: none;
		cursor: pointer;
		background: none;
	}
	.placeholder {
		height: 100%;
		display: flex;
		align-items: center;
		justify-content: center;
		color: var(--text-dim);
	}
	.status {
		position: absolute;
		left: 12px;
		bottom: 12px;
		right: 12px;
		padding: 8px 12px;
		background: rgba(0, 0, 0, 0.6);
		border-radius: 6px;
		color: var(--text);
		font-size: 13px;
	}
	.status.error {
		color: var(--error);
	}
	.status-details {
		margin-top: 6px;
		font-size: 11px;
		color: var(--text-dim);
		white-space: pre-wrap;
		word-break: break-all;
		max-height: 120px;
		overflow-y: auto;
	}
	.details-link {
		background: none;
		border: none;
		color: var(--accent);
		font-size: 11px;
		margin-left: 6px;
		cursor: pointer;
		padding: 0;
	}
	.settings-header {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 12px 15px;
		border-bottom: 1px solid var(--panel-border);
	}
	.settings-header h2 {
		margin: 0;
		font-size: 15px;
	}
	.back-btn {
		background: none;
		border: none;
		color: var(--text-dim);
		cursor: pointer;
		font-size: 13px;
		padding: 4px 0;
	}
	.scroll-content {
		flex: 1;
		overflow-y: auto;
		padding: 15px;
	}
	.control-group {
		margin-bottom: 14px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.control-group label {
		font-size: 12px;
		color: var(--text-dim);
	}
	.control-group input,
	.control-group select {
		background: var(--bg);
		border: 1px solid var(--panel-border);
		color: var(--text);
		border-radius: 6px;
		padding: 8px 10px;
		font-size: 13px;
	}
	hr {
		border: 0;
		border-top: 1px solid var(--panel-border);
		margin: 16px 0;
	}
	.btn.full {
		width: 100%;
	}
	.actions {
		padding: 15px;
		border-top: 1px solid var(--panel-border);
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.action-row {
		display: flex;
		gap: 10px;
	}
	.action-row .btn {
		flex: 1;
	}
	.json-panel {
		position: absolute;
		left: 300px;
		right: 0;
		bottom: 0;
		background: var(--bg);
		border-top: 1px solid var(--panel-border);
		display: flex;
		flex-direction: column;
		box-shadow: 0 -8px 24px rgba(0, 0, 0, 0.35);
	}
	.resize-handle {
		position: absolute;
		top: -5px;
		left: 0;
		right: 0;
		height: 9px;
		cursor: ns-resize;
		z-index: 1;
		touch-action: none;
	}
	.resize-handle::after {
		content: '';
		position: absolute;
		top: 3px;
		left: 50%;
		transform: translateX(-50%);
		width: 40px;
		height: 3px;
		border-radius: 2px;
		background: var(--panel-border);
		transition: background 0.15s ease;
	}
	.resize-handle:hover::after,
	.resize-handle:active::after {
		background: var(--accent);
	}
	.json-panel-header {
		display: flex;
		align-items: center;
		justify-content: space-between;
		border-bottom: 1px solid var(--panel-border);
		background: var(--panel);
	}
	.tabs {
		display: flex;
	}
	.tabs button {
		background: none;
		border: none;
		color: var(--text-dim);
		padding: 10px 16px;
		font-size: 13px;
		cursor: pointer;
		border-bottom: 2px solid transparent;
	}
	.tabs button.active {
		color: var(--text);
		border-bottom-color: var(--accent);
	}
	.close-btn {
		background: none;
		border: none;
		color: var(--text-dim);
		cursor: pointer;
		font-size: 16px;
		padding: 8px 14px;
	}
	.json-panel-body {
		flex: 1;
		min-height: 0;
	}
</style>
