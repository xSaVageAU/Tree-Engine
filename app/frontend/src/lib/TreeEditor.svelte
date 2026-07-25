<script lang="ts">
	// The workbench: a datapack explorer, tabbed documents, a permanently-docked
	// JSON editor and a live preview, side by side - VS Code shaped rather than
	// viewport-primary-with-a-drawer. The explorer mirrors real on-disk paths
	// (data/tree_engine/worldgen/configured_feature/<id>.json, .../placed_feature/
	// <id>.json) rather than an abstracted "tree browser" list, so what you click
	// is exactly what's on disk - no separate mental model to distrust.
	//
	// Document model: each open tree is a Doc holding the *text* of its two
	// datapack files. The text is authoritative - it is never re-serialised from a
	// parsed object while the user types, because that rewrites the buffer under
	// the cursor. JSON is parsed on demand (to generate, or to save). The two
	// files share one Doc/tab (one save action, one rename) but are reachable as
	// two separate real-looking entries in the explorer.
	import { onDestroy, onMount, tick } from 'svelte'
	import { EnsureAssets, GetCurrentProject, OpenInstanceFolder } from '../../wailsjs/go/main/App'
	import {
		BackendError,
		getFeature,
		previewTree,
		type ModConnection,
	} from '../renderer/mod-client'
	import {
		deleteTree,
		closeProject as closeProjectRequest,
		ensureSession,
		openProjectFolder,
		getPlacement,
		getTree,
		listReplacers,
		listTrees,
		savePlacement,
		saveTree,
		type Replacer,
	} from '../renderer/project-client'
	import { previewChunk } from '../renderer/mod-client'
	import { BIOME_COLORS, DEFAULT_BIOME } from '../renderer/biome-colors'
	import { takeRenderTimings, TreePreview } from '../renderer/preview'
	import type { ApiBlock } from '../renderer/structure'
	import BenchmarkModal from './BenchmarkModal.svelte'
	import CommandPalette, { type Command } from './CommandPalette.svelte'
	import Icon from './Icon.svelte'
	import ImportModal from './ImportModal.svelte'
	import MonacoEditor, { disposeDocModels, setModelText } from './MonacoEditor.svelte'
	import ReplacersPanel from './ReplacersPanel.svelte'

	let { conn }: { conn: ModConnection } = $props()

	const REPLACERS_KEY = 'replacers'
	const WORLD_KEY = 'world'

	// The starting point for a new tree, taken from the game's own registry.
	// Hardcoding it here is what broke "New Tree" on 26.2 - the literal still
	// carried the pre-26.2 shape - so the template is fetched instead of typed.
	const NEW_TREE_TEMPLATE = 'minecraft:oak'

	interface DocStatus {
		message: string
		error: boolean
		details: string
	}

	interface Doc {
		key: string
		kind: 'tree' | 'replacers' | 'world'
		/** Saved id on disk; null for a document that has never been saved. */
		id: string | null
		name: string
		treeText: string
		placementText: string
		savedTreeText: string
		savedPlacementText: string
		jsonTab: 'tree' | 'placement'
		status: DocStatus
		blockCount: number
		genMs: number
	}

	// Generated blocks are deliberately kept out of $state: a tree can be
	// thousands of block objects and deep reactivity would proxy every one.
	const blockCache = new Map<string, ApiBlock[]>()

	let docs = $state<Doc[]>([])
	let activeKey = $state<string | null>(null)
	let untitledSeq = 0

	// A direct reference to the doc pending a close confirmation, not a key -
	// saving can rename/rekey the doc mid-flow, and a key-based lookup would
	// lose track of it (or worse, close the dialog early) when that happens.
	let closingDoc = $state<Doc | null>(null)
	let closingSaving = $state(false)

	let trees = $state<string[]>([])
	let replacers = $state<Replacer[]>([])
	let searchQuery = $state('')

	// Editor/preview split - percentage width given to the editor pane.
	let editorWidthPct = $state(55)
	let splitBodyEl = $state<HTMLDivElement | undefined>(undefined)

	let importModalOpen = $state(false)
	let benchmarkModalOpen = $state(false)
	let paletteMode = $state<'commands' | 'files' | null>(null)

	// World preview state. Chunk coordinates rather than block coordinates,
	// since that is the unit generation works in.
	let worldChunkX = $state(0)
	let worldChunkZ = $state(0)
	let worldRadius = $state(0)
	let worldInfo = $state('')

	let generating = $state(false)
	let saving = $state(false)
	let cursor = $state({ line: 1, column: 1 })

	let assetsBaseURL = $state('')
	let assetsError = $state('')
	let projectName = $state('')

	let canvasEl = $state<HTMLCanvasElement | undefined>(undefined)
	let preview: TreePreview | undefined

	let biome = $state(DEFAULT_BIOME)
	const biomeOptions = Object.keys(BIOME_COLORS)
	let autoRotateOn = $state(false)
	let showGridOn = $state(true)
	let bgColor = $state('#0b0f0c')

	const activeDoc = $derived(docs.find((d) => d.key === activeKey) ?? null)
	const activeTree = $derived(activeDoc && activeDoc.kind === 'tree' ? activeDoc : null)
	const activeWorld = $derived(activeDoc && activeDoc.kind === 'world' ? activeDoc : null)
	const filteredTrees = $derived(trees.filter((t) => t.toLowerCase().includes(searchQuery.toLowerCase())))
	const dirtyCount = $derived(docs.filter(isDirty).length)

	function isDirty(d: Doc): boolean {
		if (d.kind !== 'tree') return false
		return d.treeText !== d.savedTreeText || d.placementText !== d.savedPlacementText
	}

	function docLabel(d: Doc): string {
		if (d.kind === 'replacers') return 'Tree Replacers'
		if (d.kind === 'world') return 'World Preview'
		return (d.name.trim() || 'untitled') + '.json'
	}

	function docUri(d: Doc, which: 'tree' | 'placement'): string {
		return `inmemory://te/${d.key.replace(/[^a-z0-9]/gi, '_')}/${which}.json`
	}

	// --- Loading -------------------------------------------------------------

	onMount(() => {
		EnsureAssets().then((assets) => {
			if (assets.ready) {
				assetsBaseURL = assets.baseURL
				// A document opened while assets were still downloading skipped its
				// first generate; now that they are here, catch it up.
				if (activeTree) scheduleGenerate(true)
			} else {
				assetsError = assets.error
			}
		})
		GetCurrentProject().then((p) => {
			projectName = p.name
		})
		void refreshLibrary()
	})

	// Monaco models must not be disposed while the editor still has one mounted -
	// that leaves it rendering a dead model. Defer until Svelte has flushed the
	// document switch (or unmounted the editor entirely).
	function modelPrefix(key: string): string {
		return `inmemory://te/${key.replace(/[^a-z0-9]/gi, '_')}/`
	}

	function deferDisposeModels(key: string): void {
		const prefix = modelPrefix(key)
		void tick().then(() => disposeDocModels(prefix))
	}

	onDestroy(() => {
		window.removeEventListener('resize', onWindowResize)
	})

	function onWindowResize(): void {
		if (canvasEl) preview?.resize(canvasEl)
	}

	async function refreshLibrary(): Promise<void> {
		try {
			trees = await listTrees()
		} catch (e) {
			console.error('Failed to load trees', e)
		}
		try {
			replacers = await listReplacers()
		} catch (e) {
			console.error('Failed to load replacers', e)
		}
	}

	// --- Document lifecycle --------------------------------------------------

	function makeDoc(partial: Partial<Doc> & { key: string; kind: Doc['kind'] }): Doc {
		return {
			id: null,
			name: '',
			treeText: '',
			placementText: '',
			savedTreeText: '',
			savedPlacementText: '',
			jsonTab: 'tree',
			status: { message: 'Ready', error: false, details: '' },
			blockCount: 0,
			genMs: 0,
			...partial,
		}
	}

	async function openTree(id: string, which: 'tree' | 'placement' = 'tree'): Promise<void> {
		const key = `tree:${id}`
		const existing = docs.find((d) => d.key === key)
		if (existing) {
			existing.jsonTab = which
			setActive(key)
			return
		}
		try {
			const treeJson = JSON.parse(await getTree(id))
			let placementJson: any
			try {
				placementJson = JSON.parse(await getPlacement(id))
			} catch {
				placementJson = { feature: `tree_engine:${id}`, placement: [] }
			}
			const treeText = JSON.stringify(treeJson, null, 2)
			const placementText = JSON.stringify(placementJson, null, 2)
			docs = [
				...docs,
				makeDoc({
					key,
					kind: 'tree',
					id,
					name: id,
					treeText,
					placementText,
					savedTreeText: treeText,
					savedPlacementText: placementText,
					jsonTab: which,
				}),
			]
			setActive(key)
		} catch (e) {
			alert('Failed to load tree: ' + (e as Error).message)
		}
	}

	async function newTree(): Promise<void> {
		untitledSeq++
		const key = `new:${untitledSeq}`
		let treeText = '{}'
		try {
			treeText = JSON.stringify(await getFeature(conn, NEW_TREE_TEMPLATE), null, 2)
		} catch (e) {
			console.error('Failed to fetch the new-tree template', e)
		}
		docs = [
			...docs,
			makeDoc({
				key,
				kind: 'tree',
				treeText,
				placementText: JSON.stringify({ feature: 'tree_engine:', placement: [] }, null, 2),
			}),
		]
		setActive(key)
	}

	async function importVanillaTree(id: string): Promise<void> {
		importModalOpen = false
		try {
			const featureJson = await getFeature(conn, id)
			const name = id.includes(':') ? id.split(':')[1] : id
			untitledSeq++
			const key = `new:${untitledSeq}`
			docs = [
				...docs,
				makeDoc({
					key,
					kind: 'tree',
					name,
					treeText: JSON.stringify(featureJson, null, 2),
					placementText: JSON.stringify({ feature: `tree_engine:${name}`, placement: [] }, null, 2),
				}),
			]
			setActive(key)
		} catch (e) {
			alert('Failed to import tree: ' + (e as Error).message)
		}
	}

	// Jumps somewhere else in the world. The managed server has one seed, so
	// sampling different terrain means moving rather than reseeding - and
	// moving is instant, where a new seed would mean regenerating the world.
	//
	// Somewhere in a +/-1000 chunk range is far enough to land in genuinely
	// different biomes without being so remote that nothing is cached.
	function randomizeLocation(): void {
		worldChunkX = Math.floor(Math.random() * 2001) - 1000
		worldChunkZ = Math.floor(Math.random() * 2001) - 1000
		scheduleGenerate(true, true)
	}

	function openWorld(): void {
		if (!docs.some((d) => d.key === WORLD_KEY)) {
			docs = [...docs, makeDoc({ key: WORLD_KEY, kind: 'world' })]
		}
		setActive(WORLD_KEY)
	}

	function openReplacers(): void {
		if (!docs.some((d) => d.key === REPLACERS_KEY)) {
			docs = [...docs, makeDoc({ key: REPLACERS_KEY, kind: 'replacers' })]
		}
		setActive(REPLACERS_KEY)
	}

	// Switching tabs re-renders from the cached block list - no server round-trip.
	function setActive(key: string): void {
		activeKey = key
		const d = docs.find((x) => x.key === key)
		if (d?.kind !== 'tree' && d?.kind !== 'world') return
		const cached = blockCache.get(key)
		if (cached) void renderBlocks(cached)
		else scheduleGenerate(true)
	}

	// Closes unconditionally. Callers that need to guard against losing unsaved
	// work go through requestClose instead.
	function closeDoc(key: string): void {
		const d = docs.find((x) => x.key === key)
		if (!d) return
		const idx = docs.findIndex((x) => x.key === key)
		deferDisposeModels(key)
		blockCache.delete(key)
		docs = docs.filter((x) => x.key !== key)
		if (activeKey === key) {
			const next = docs[Math.min(idx, docs.length - 1)]
			if (next) setActive(next.key)
			else activeKey = null
		}
		if (closingDoc?.key === key) closingDoc = null
	}

	// Entry point for user-initiated close (tab X, middle-click, Ctrl+W). Dirty
	// tree docs get a confirmation dialog with an inline save option instead of
	// being closed outright - a blank "untitled" tree is dirty from the moment
	// it's created, so this is also what makes new tabs closeable at all.
	function requestClose(key: string): void {
		const d = docs.find((x) => x.key === key)
		if (!d) return
		if (d.kind === 'tree' && isDirty(d)) {
			setActive(key)
			closingDoc = d
		} else {
			closeDoc(key)
		}
	}

	function cancelClose(): void {
		closingDoc = null
	}

	function discardClose(): void {
		if (closingDoc) closeDoc(closingDoc.key)
	}

	async function saveAndClose(): Promise<void> {
		const d = closingDoc
		if (!d) return
		closingSaving = true
		const ok = await saveDoc(d)
		closingSaving = false
		// d.key may have changed (untitled -> tree:<name>) - saveDoc mutates the
		// same object in place, so d.key already reflects the rename.
		if (ok) closeDoc(d.key)
	}

	// --- Editing -------------------------------------------------------------

	function onBufferChange(which: 'tree' | 'placement', text: string): void {
		const d = activeTree
		if (!d) return
		if (which === 'tree') {
			d.treeText = text
			scheduleGenerate()
		} else {
			d.placementText = text
		}
	}

	// A project's session and cached geometry belong to that project. Dropping
	// them on a switch stops the next preview resolving against the datapack of
	// the project just left.
	async function switchProject(): Promise<void> {
		blockCache.clear()
		await openProjectFolder()
	}

	async function closeProject(): Promise<void> {
		blockCache.clear()
		await closeProjectRequest()
	}

	// --- Generation ----------------------------------------------------------

	let genSeq = 0
	let genTimer: ReturnType<typeof setTimeout> | undefined

	// Generation is seeded, so the same config and seed always produce the same
	// tree. That is what makes editing legible: tweaking a value shows the
	// effect of the tweak rather than a differently-shuffled tree. Rerolling is
	// therefore an explicit action, not a side effect of typing.
	let previewSeed = $state(Math.floor(Math.random() * 2 ** 31))

	function rerollSeed(): void {
		previewSeed = Math.floor(Math.random() * 2 ** 31)
	}

	function scheduleGenerate(immediate = false, reroll = false): void {
		clearTimeout(genTimer)
		if (reroll) rerollSeed()
		if (immediate) {
			void runGenerate()
			return
		}
		// Every keystroke that happens to parse used to fire a server round-trip.
		genTimer = setTimeout(() => void runGenerate(), 300)
	}

	// Renders are serialised through this chain. Two generations can finish
	// close enough together that both reach the renderer while the first is
	// still awaiting - which previously let both see a missing preview and
	// construct one, double-registering the resize listener and leaving which
	// render landed last up to chance. Chaining makes the last call win.
	let renderChain: Promise<void> = Promise.resolve()

	function renderBlocks(blocks: ApiBlock[]): Promise<void> {
		renderChain = renderChain
			.catch(() => {})
			.then(() => applyBlocks(blocks))
		return renderChain
	}

	async function applyBlocks(blocks: ApiBlock[]): Promise<void> {
		await tick()
		if (!canvasEl || !assetsBaseURL) return
		if (!preview) {
			preview = await TreePreview.create(canvasEl, blocks, assetsBaseURL, { showGrid: showGridOn, biome })
			preview.setAutoRotate(autoRotateOn)
			window.addEventListener('resize', onWindowResize)
		} else {
			await preview.setBlocks(blocks, assetsBaseURL, biome)
		}
	}

	async function runGenerate(): Promise<void> {
		if (activeWorld) {
			await runWorldGenerate()
			return
		}
		const doc = activeTree
		if (!doc || !assetsBaseURL) return

		let feature: any
		try {
			feature = JSON.parse(doc.treeText)
		} catch (e) {
			doc.status = { message: 'Invalid JSON - preview not updated', error: true, details: (e as Error).message }
			return
		}

		// Requests are sequenced: a slow response that has been superseded by a
		// newer edit must not overwrite the preview with a stale tree.
		const seq = ++genSeq
		const docKey = doc.key
		generating = true
		doc.status = { message: 'Generating...', error: false, details: '' }
		const started = performance.now()

		try {
			// A session is optional. It is null for a project with nothing
			// saved yet, and a failure to build one must not block previewing
			// the buffer in front of you - a broken file elsewhere in the
			// project would otherwise take the whole preview down with it.
			let sessionId: string | null = null
			let sessionNote = ''
			try {
				sessionId = await ensureSession(conn)
			} catch (e) {
				sessionNote = ' (project datapack failed to load)'
				console.error('Failed to build preview session', e)
			}

			const result = await previewTree(conn, {
				sessionId: sessionId ?? undefined,
				feature,
				biome,
				seed: previewSeed,
			})
			if (seq !== genSeq) return
			const blocks = result.blocks.filter((b) => b.name !== 'minecraft:air')

			const target = docs.find((d) => d.key === docKey)
			if (!target) return
			blockCache.set(docKey, blocks)
			target.blockCount = blocks.length
			target.genMs = Math.round(performance.now() - started)
			target.status = {
				message: `Generated ${blocks.length} blocks${sessionNote}`,
				error: sessionNote !== '',
				details: '',
			}
			if (activeKey === docKey) await renderBlocks(blocks)
		} catch (e) {
			if (seq !== genSeq) return
			const err = e as BackendError
			doc.status = { message: err.message, error: true, details: err.detail ?? '' }
		} finally {
			if (seq === genSeq) generating = false
		}
	}

	// The world preview shows the whole datapack applied to real terrain, so
	// unlike a single-tree preview it always wants a session. Without one the
	// backend generates plain vanilla, which is a legitimate answer but not the
	// question this view is asking.
	async function runWorldGenerate(): Promise<void> {
		const doc = activeWorld
		if (!doc || !assetsBaseURL) return

		const seq = ++genSeq
		const docKey = doc.key
		generating = true
		doc.status = {
			message: 'Generating chunks (new terrain can take a moment)...',
			error: false,
			details: '',
		}
		const started = performance.now()

		try {
			const sessionId = await ensureSession(conn)
			const tRequest = performance.now()
			const result = await previewChunk(conn, {
				sessionId: sessionId ?? undefined,
				chunkX: worldChunkX,
				chunkZ: worldChunkZ,
				radius: worldRadius,
				seed: previewSeed,
			})
			const serverMs = Math.round(performance.now() - tRequest)
			if (seq !== genSeq) return

			const blocks = result.blocks.filter((b) => b.name !== 'minecraft:air')
			const target = docs.find((d) => d.key === docKey)
			if (!target) return
			blockCache.set(docKey, blocks)
			target.blockCount = blocks.length
			target.genMs = Math.round(performance.now() - started)
			target.status = {
				message: `${result.chunkCount} chunk${result.chunkCount === 1 ? '' : 's'}, ${blocks.length} blocks`,
				error: false,
				details: '',
			}
			const source = result.datapackApplied ? `${result.decoratedCount} placed` : 'vanilla only'
			if (activeKey === docKey) await renderBlocks(blocks)

			// Attribute the time rather than reporting one opaque total: server
			// generation, asset fetching and meshing fail slow in very
			// different ways, and knowing which one is the problem is the
			// whole point of showing this.
			const render = takeRenderTimings()
			const phases = render
				? `server ${serverMs}ms · build ${render.buildMs}ms · assets ${render.assetsMs}ms · mesh ${render.meshMs}ms`
				: `server ${serverMs}ms`
			worldInfo = `y ${result.minY}..${result.maxY} · ${source} · ${phases}`
		} catch (e) {
			if (seq !== genSeq) return
			const err = e as BackendError
			doc.status = { message: err.message, error: true, details: err.detail ?? '' }
		} finally {
			if (seq === genSeq) generating = false
		}
	}

	// --- Saving --------------------------------------------------------------

	// Saves a specific document (not necessarily the active one - the
	// close-confirmation dialog can save a background tab). Returns whether the
	// file actually made it to disk.
	async function saveDoc(d: Doc): Promise<boolean> {
		const name = d.name.trim().toLowerCase()
		if (!name) {
			d.status = { message: 'Give the tree a name before saving', error: true, details: '' }
			return false
		}
		if (!/^[a-z0-9_]+$/.test(name)) {
			d.status = { message: 'Name may only contain letters, numbers and underscores', error: true, details: '' }
			return false
		}

		let treeJson: any
		let placementJson: any
		try {
			treeJson = JSON.parse(d.treeText)
		} catch (e) {
			d.status = { message: 'Tree config is not valid JSON', error: true, details: (e as Error).message }
			d.jsonTab = 'tree'
			return false
		}
		try {
			placementJson = JSON.parse(d.placementText)
		} catch (e) {
			d.status = { message: 'Placement rules are not valid JSON', error: true, details: (e as Error).message }
			d.jsonTab = 'placement'
			return false
		}

		saving = true
		try {
			await saveTree(name, JSON.stringify(treeJson, null, 2))

			// Keep the placed feature pointing at the (possibly renamed) tree, and
			// reflect that in the buffer rather than silently diverging from it.
			if (placementJson && typeof placementJson === 'object') {
				placementJson.feature = `tree_engine:${name}`
				const rewritten = JSON.stringify(placementJson, null, 2)
				if (rewritten !== d.placementText) {
					d.placementText = rewritten
					setModelText(docUri(d, 'placement'), rewritten)
				}
				await savePlacement(name, JSON.stringify(placementJson, null, 2)).catch((e) => console.error('Failed to save placement:', e))
			}

			d.savedTreeText = d.treeText
			d.savedPlacementText = d.placementText

			// A new document becomes a real file, so it takes on that identity.
			if (d.key !== `tree:${name}`) rekeyDoc(d, `tree:${name}`, name)
			else d.id = name

			await refreshLibrary()
			// Saving writes datapack files directly; the next preview picks
			// them up by uploading a fresh session. Nothing to reload.
			d.status = { message: 'Saved', error: false, details: '' }
			return true
		} catch (e) {
			d.status = { message: 'Failed to save: ' + (e as Error).message, error: true, details: '' }
			return false
		} finally {
			saving = false
		}
	}

	async function saveActive(): Promise<void> {
		if (activeTree) await saveDoc(activeTree)
	}

	// Moves a document to a new key, carrying its buffers across. The old Monaco
	// models are dropped so the new URI seeds cleanly from the saved text.
	function rekeyDoc(d: Doc, nextKey: string, name: string): void {
		const oldKey = d.key

		// Evict any other document already sitting on the target key. It cannot be
		// the active one (that is `d`), so its models are not mounted anywhere and
		// are safe to drop synchronously - deferring here would instead destroy the
		// *new* models created for the renamed document.
		if (docs.some((x) => x.key === nextKey)) {
			disposeDocModels(modelPrefix(nextKey))
			blockCache.delete(nextKey)
			docs = docs.filter((x) => x.key !== nextKey)
		}

		deferDisposeModels(oldKey)
		const cached = blockCache.get(oldKey)
		blockCache.delete(oldKey)
		if (cached) blockCache.set(nextKey, cached)
		d.key = nextKey
		d.id = name
		d.name = name
		if (activeKey === oldKey) activeKey = nextKey
	}

	async function saveAll(): Promise<void> {
		for (const d of docs.filter(isDirty)) {
			await saveDoc(d)
		}
	}

	async function deleteActive(): Promise<void> {
		const d = activeTree
		if (!d?.id) return
		if (!confirm(`Delete "${d.id}"? This removes the file from the datapack.`)) return
		try {
			await deleteTree(d.id)
			await refreshLibrary()
			closeDoc(d.key)
		} catch (e) {
			alert('Error deleting tree: ' + (e as Error).message)
		}
	}

	// --- Viewport ------------------------------------------------------------

	function onBiomeChange(): void {
		preview?.setBiome(biome)
	}

	function toggleAutoRotate(): void {
		autoRotateOn = !autoRotateOn
		preview?.setAutoRotate(autoRotateOn)
	}

	function toggleShowGrid(): void {
		showGridOn = !showGridOn
		preview?.setShowGrid(showGridOn)
	}

	// Drags the boundary between the editor and preview panes. Widths are kept
	// as percentages of the split container so the layout stays proportional
	// when the window itself is resized.
	function startSplitResize(e: PointerEvent): void {
		e.preventDefault()
		const rect = splitBodyEl?.getBoundingClientRect()
		if (!rect) return
		const rectLeft = rect.left
		const rectWidth = rect.width
		document.body.style.userSelect = 'none'

		function onMove(ev: PointerEvent): void {
			const pct = ((ev.clientX - rectLeft) / rectWidth) * 100
			editorWidthPct = Math.min(78, Math.max(28, pct))
			if (canvasEl) preview?.resize(canvasEl)
		}
		function onUp(): void {
			document.body.style.userSelect = ''
			window.removeEventListener('pointermove', onMove)
			window.removeEventListener('pointerup', onUp)
		}
		window.addEventListener('pointermove', onMove)
		window.addEventListener('pointerup', onUp)
	}

	// --- Commands ------------------------------------------------------------

	const commands = $derived<Command[]>([
		{ id: 'new', label: 'New Tree', icon: 'plus', keybinding: 'Ctrl+N', run: () => void newTree() },
		{ id: 'import', label: 'Import Tree...', icon: 'import', run: () => (importModalOpen = true) },
		{ id: 'replacers', label: 'Open Tree Replacers', icon: 'shuffle', run: openReplacers },
		{ id: 'save', label: 'Save Tree', icon: 'save', keybinding: 'Ctrl+S', run: () => void saveActive() },
		{ id: 'save-all', label: 'Save All', icon: 'save', run: () => void saveAll() },
		{
			id: 'regen',
			label: 'Regenerate Preview',
			icon: 'refresh',
			keybinding: 'Ctrl+Enter',
			run: () => scheduleGenerate(true, true),
		},
		{ id: 'benchmark', label: 'Run Benchmark', icon: 'bolt', run: () => (benchmarkModalOpen = true) },
		{ id: 'toggle-grid', label: 'Toggle Grid', icon: 'grid', run: toggleShowGrid },
		{ id: 'toggle-rotate', label: 'Toggle Auto-Rotate', icon: 'orbit', run: toggleAutoRotate },
		{ id: 'delete', label: 'Delete Tree', icon: 'trash', run: () => void deleteActive() },
		{
			id: 'close',
			label: 'Close Tab',
			icon: 'close',
			keybinding: 'Ctrl+W',
			run: () => activeKey && requestClose(activeKey),
		},
		{ id: 'refresh', label: 'Refresh Library', icon: 'refresh', run: () => void refreshLibrary() },
		{ id: 'folder', label: 'Open Instance Folder', icon: 'folder', run: () => OpenInstanceFolder() },
		{ id: 'switch-project', label: 'Switch Project...', icon: 'folder', run: () => void switchProject() },
		{ id: 'close-project', label: 'Close Project', icon: 'close', run: () => void closeProject() },
	])

	const fileCommands = $derived<Command[]>(
		trees.map((id) => ({
			id: `open:${id}`,
			label: `${id}.json`,
			hint: 'configured_feature',
			icon: 'tree',
			run: () => void openTree(id),
		})),
	)

	function onWindowKey(e: KeyboardEvent): void {
		// The close-confirmation dialog owns the keyboard while it's open - global
		// shortcuts (Ctrl+S in particular) must not fire behind it.
		if (closingDoc) {
			if (e.key === 'Escape') {
				e.preventDefault()
				cancelClose()
			}
			return
		}
		const ctrl = e.ctrlKey || e.metaKey
		if (ctrl && e.shiftKey && e.key.toLowerCase() === 'p') {
			e.preventDefault()
			paletteMode = 'commands'
		} else if (ctrl && e.key.toLowerCase() === 'p') {
			e.preventDefault()
			paletteMode = 'files'
		} else if (ctrl && e.key.toLowerCase() === 's') {
			e.preventDefault()
			void saveActive()
		} else if (ctrl && e.key.toLowerCase() === 'n') {
			e.preventDefault()
			void newTree()
		} else if (ctrl && e.key.toLowerCase() === 'w') {
			e.preventDefault()
			if (activeKey) requestClose(activeKey)
		} else if (ctrl && e.key === 'Enter') {
			e.preventDefault()
			// Same action as the Regenerate button, so it rerolls too.
			scheduleGenerate(true, true)
		}
	}
</script>

<svelte:window onkeydown={onWindowKey} />

<div class="workbench">
	<div class="workbench-body">
		<aside class="explorer">
			<div class="panel-head">
				<div class="eyebrow"><Icon name="layers" size={13} />Explorer</div>
				<button class="icon-btn sm" title="Refresh library" aria-label="Refresh library" onclick={() => void refreshLibrary()}>
					<Icon name="refresh" size={14} />
				</button>
			</div>

			<div class="pack-root">
				<Icon name="folder" size={13} />
				<span class="pack-name mono">{projectName || 'project'}</span>
				<span class="pack-tag">datapack</span>
			</div>

			<div class="explorer-search">
				<div class="field-search">
					<Icon name="search" size={14} />
					<input type="search" placeholder="Filter files..." bind:value={searchQuery} />
				</div>
			</div>

			<div class="explorer-tree">
				<div class="group">
					<div class="group-head">
						<span class="caret">▾</span>
						<span class="group-name mono">configured_feature</span>
						<span class="count-badge">{filteredTrees.length}</span>
					</div>
					<div class="group-path mono">data/tree_engine/worldgen</div>
					{#each filteredTrees as id (id)}
						<div
							class="file-row"
							class:open={docs.some((d) => d.key === `tree:${id}`)}
							class:on={activeKey === `tree:${id}` && activeTree?.jsonTab === 'tree'}
							role="button"
							tabindex="0"
							onclick={() => void openTree(id, 'tree')}
							onkeydown={(e) => e.key === 'Enter' && void openTree(id, 'tree')}
						>
							<Icon name="tree" size={13} />
							<span class="file-name">{id}.json</span>
							{#if docs.find((d) => d.key === `tree:${id}` && isDirty(d))}<span class="dot-dirty"></span>{/if}
						</div>
					{:else}
						<div class="group-empty">{searchQuery ? 'No matches' : 'No trees yet'}</div>
					{/each}
				</div>

				<div class="group">
					<div class="group-head">
						<span class="caret">▾</span>
						<span class="group-name mono">placed_feature</span>
						<span class="count-badge">{filteredTrees.length}</span>
					</div>
					<div class="group-path mono">data/tree_engine/worldgen</div>
					{#each filteredTrees as id (id)}
						<div
							class="file-row"
							class:open={docs.some((d) => d.key === `tree:${id}`)}
							class:on={activeKey === `tree:${id}` && activeTree?.jsonTab === 'placement'}
							role="button"
							tabindex="0"
							onclick={() => void openTree(id, 'placement')}
							onkeydown={(e) => e.key === 'Enter' && void openTree(id, 'placement')}
						>
							<Icon name="tree" size={13} />
							<span class="file-name">{id}.json</span>
						</div>
					{:else}
						<div class="group-empty">{searchQuery ? 'No matches' : 'No placement files yet'}</div>
					{/each}
				</div>

				<div class="group">
					<div class="group-head">
						<span class="caret">▾</span>
						<span class="group-name mono">replacers</span>
						<span class="count-badge">{replacers.length}</span>
					</div>
					<div class="group-path mono">data/minecraft/worldgen</div>
					<div
						class="file-row"
						class:on={activeKey === REPLACERS_KEY}
						role="button"
						tabindex="0"
						onclick={openReplacers}
						onkeydown={(e) => e.key === 'Enter' && openReplacers()}
					>
						<Icon name="shuffle" size={13} />
						<span class="file-name">Manage replacers</span>
					</div>
				</div>

				<div class="explorer-group">
					<div class="group-head">
						<span class="caret">▾</span>
						<span class="group-name mono">world</span>
					</div>
					<div class="group-path mono">real terrain · this datapack</div>
					<div
						class="file-row"
						class:on={activeKey === WORLD_KEY}
						role="button"
						tabindex="0"
						onclick={openWorld}
						onkeydown={(e) => e.key === 'Enter' && openWorld()}
					>
						<Icon name="grid" size={13} />
						<span class="file-name">World preview</span>
					</div>
				</div>
			</div>

			<div class="explorer-foot">
				<button class="btn full" onclick={() => void newTree()}><Icon name="plus" size={14} />New Tree</button>
				<button class="btn secondary full" onclick={() => (importModalOpen = true)}>
					<Icon name="import" size={14} />Import Tree
				</button>
			</div>
		</aside>

		<div class="main">
			<div class="tabstrip" role="tablist">
				{#each docs as d (d.key)}
					<div
						class="tab"
						class:on={activeKey === d.key}
						role="tab"
						tabindex="0"
						aria-selected={activeKey === d.key}
						onclick={() => setActive(d.key)}
						onkeydown={(e) => e.key === 'Enter' && setActive(d.key)}
						onauxclick={(e) => e.button === 1 && requestClose(d.key)}
					>
						<Icon name={d.kind === 'replacers' ? 'shuffle' : d.kind === 'world' ? 'grid' : 'tree'} size={13} />
						<span class="tab-label">{docLabel(d)}</span>
						<span class="tab-indicator">
							{#if isDirty(d)}<span class="tab-dirty" title="Unsaved changes"></span>{/if}
							<span
								class="tab-close"
								role="button"
								tabindex="-1"
								aria-label="Close tab"
								onclick={(e) => {
									e.stopPropagation()
									requestClose(d.key)
								}}
								onkeydown={(e) => e.key === 'Enter' && requestClose(d.key)}
							>
								<Icon name="close" size={12} />
							</span>
						</span>
					</div>
				{/each}
			</div>

			{#if activeTree}
				<div class="doc-bar">
					<div class="breadcrumb mono">
						<span>tree_engine</span><span class="crumb-sep">›</span><span>worldgen</span><span class="crumb-sep">›</span
						><span>{activeTree.jsonTab === 'tree' ? 'configured_feature' : 'placed_feature'}</span><span
							class="crumb-sep">›</span
						>
					</div>
					<div class="name-field">
						<input
							type="text"
							placeholder="untitled"
							spellcheck="false"
							bind:value={activeTree.name}
							aria-label="Tree name"
						/>
						<span class="name-ext mono">.json</span>
					</div>
					<div class="doc-actions">
						<button class="icon-btn" title="Regenerate preview (Ctrl+Enter)" aria-label="Regenerate preview" disabled={generating} onclick={() => scheduleGenerate(true, true)}>
							<Icon name="refresh" size={15} class={generating ? 'spin' : ''} />
						</button>
						<button class="icon-btn" title="Run benchmark" aria-label="Run benchmark" onclick={() => (benchmarkModalOpen = true)}>
							<Icon name="bolt" size={15} />
						</button>
						<button class="icon-btn danger" title="Delete tree" aria-label="Delete tree" disabled={!activeTree.id} onclick={() => void deleteActive()}>
							<Icon name="trash" size={15} />
						</button>
						<span class="bar-sep"></span>
						<button class="btn btn-sm" disabled={saving} onclick={() => void saveActive()}>
							<Icon name="save" size={13} />{saving ? 'Saving...' : 'Save'}
						</button>
					</div>
				</div>
			{/if}

			<div class="split-body" bind:this={splitBodyEl}>
				<div class="editor-pane" class:collapsed={!activeTree} style="flex-basis: {activeTree ? editorWidthPct : 0}%">
					{#if activeTree}
						<div class="editor-tabs">
							<div class="tabs">
								<button class:active={activeTree.jsonTab === 'tree'} onclick={() => (activeTree.jsonTab = 'tree')}>
									configured_feature
								</button>
								<button
									class:active={activeTree.jsonTab === 'placement'}
									onclick={() => (activeTree.jsonTab = 'placement')}
								>
									placed_feature
								</button>
							</div>
						</div>
						<div class="editor-host">
							{#if activeTree.jsonTab === 'tree'}
								<MonacoEditor
									uri={docUri(activeTree, 'tree')}
									value={activeTree.treeText}
									onChange={(t) => onBufferChange('tree', t)}
									onCursor={(line, column) => (cursor = { line, column })}
									onSave={() => void saveActive()}
									onPalette={() => (paletteMode = 'commands')}
									onQuickOpen={() => (paletteMode = 'files')}
								/>
							{:else}
								<MonacoEditor
									uri={docUri(activeTree, 'placement')}
									value={activeTree.placementText}
									onChange={(t) => onBufferChange('placement', t)}
									onCursor={(line, column) => (cursor = { line, column })}
									onSave={() => void saveActive()}
									onPalette={() => (paletteMode = 'commands')}
									onQuickOpen={() => (paletteMode = 'files')}
								/>
							{/if}
						</div>
					{/if}
				</div>

				{#if activeTree}
					<div
						class="vsplit-handle"
						role="separator"
						aria-orientation="vertical"
						aria-label="Resize editor/preview split"
						onpointerdown={startSplitResize}
					></div>
				{/if}

				<div class="preview-pane">
					<div class="canvas-layer" style="background: {bgColor}">
						<canvas bind:this={canvasEl}></canvas>
						<div class="viewport-vignette"></div>

						{#if activeWorld}
							<div class="viewport-toolbar">
								<div class="tool-group">
									<label class="coord-field">
										<span class="coord-label mono">x</span>
										<input type="number" bind:value={worldChunkX} aria-label="Chunk X" />
									</label>
									<label class="coord-field">
										<span class="coord-label mono">z</span>
										<input type="number" bind:value={worldChunkZ} aria-label="Chunk Z" />
									</label>
									<span class="tool-sep"></span>
									<select class="biome-select" bind:value={worldRadius} aria-label="Area size">
										<option value={0}>1 chunk</option>
										<option value={1}>3 × 3</option>
									</select>
									<span class="tool-sep"></span>
									<button
										class="tool-btn"
										title="Jump to a random location"
										disabled={generating}
										onclick={randomizeLocation}
									>
										<Icon name="shuffle" size={15} />
									</button>
									<button
										class="tool-btn"
										title="Generate (Ctrl+Enter)"
										disabled={generating}
										onclick={() => scheduleGenerate(true, true)}
									>
										<Icon name={generating ? 'spinner' : 'bolt'} size={15} class={generating ? 'spin' : ''} />
									</button>
									<button class="tool-btn" class:active={autoRotateOn} title="Auto-rotate" aria-pressed={autoRotateOn} onclick={toggleAutoRotate}>
										<Icon name="orbit" size={15} />
									</button>
								</div>
							</div>
							{#if worldInfo}
								<div class="world-readout mono">{worldInfo}</div>
							{/if}
						{:else if activeTree}
							<div class="viewport-toolbar">
								<div class="tool-group">
									<select class="biome-select" bind:value={biome} onchange={onBiomeChange} aria-label="Preview biome tint">
										{#each biomeOptions as b (b)}
											<option value={b}>{b.split('_').map((w) => w[0].toUpperCase() + w.slice(1)).join(' ')}</option>
										{/each}
									</select>
									<span class="tool-sep"></span>
									<button class="tool-btn" class:active={autoRotateOn} title="Auto-rotate" aria-pressed={autoRotateOn} onclick={toggleAutoRotate}>
										<Icon name="orbit" size={15} />
									</button>
									<button class="tool-btn" class:active={showGridOn} title="Ground grid" aria-pressed={showGridOn} onclick={toggleShowGrid}>
										<Icon name="grid" size={15} />
									</button>
									<label class="tool-btn swatch" title="Background colour">
										<span class="swatch-chip" style="background: {bgColor}"></span>
										<input type="color" bind:value={bgColor} />
									</label>
								</div>
							</div>
						{/if}

						{#if assetsError}
							<div class="floating-error">
								<Icon name="alert" size={14} /><span>Asset provisioning failed: {assetsError}</span>
							</div>
						{:else if activeTree?.status.error && activeTree.status.details}
							<div class="floating-error">
								<Icon name="alert" size={14} />
								<div>
									<div>{activeTree.status.message}</div>
									<div class="error-details mono">{activeTree.status.details}</div>
								</div>
							</div>
						{/if}
					</div>

					{#if activeDoc?.kind === 'replacers'}
						<div class="overlay-panel">
							<ReplacersPanel {conn} customTrees={trees} onBack={() => closeDoc(REPLACERS_KEY)} />
						</div>
					{:else if !activeDoc}
						<div class="overlay-panel welcome">
							<div class="welcome-inner">
								<div class="welcome-mark"><Icon name="tree" size={34} /></div>
								<h2>Tree Engine</h2>
								<p>A datapack workspace for Minecraft world-gen trees.</p>
								<div class="welcome-actions">
									<button class="btn" onclick={() => void newTree()}><Icon name="plus" size={14} />New Tree</button>
									<button class="btn secondary" onclick={() => (importModalOpen = true)}>
										<Icon name="import" size={14} />Import
									</button>
								</div>
								<dl class="shortcuts">
									<div><dt>Quick open</dt><dd><kbd>Ctrl</kbd><kbd>P</kbd></dd></div>
									<div><dt>Command palette</dt><dd><kbd>Ctrl</kbd><kbd>Shift</kbd><kbd>P</kbd></dd></div>
									<div><dt>Save</dt><dd><kbd>Ctrl</kbd><kbd>S</kbd></dd></div>
									<div><dt>Regenerate</dt><dd><kbd>Ctrl</kbd><kbd>Enter</kbd></dd></div>
								</dl>
							</div>
						</div>
					{/if}
				</div>
			</div>
		</div>
	</div>

	<footer class="statusbar">
		<div class="status-left">
			<span class="status-cell accent"><span class="live-dot"></span>127.0.0.1:{conn.port}</span>
			{#if activeTree}
				<span class="status-cell">{docLabel(activeTree)}</span>
				{#if isDirty(activeTree)}<span class="status-cell warn">unsaved</span>{/if}
			{/if}
			{#if dirtyCount > 1}<span class="status-cell warn">{dirtyCount} unsaved</span>{/if}
		</div>
		<div class="status-right">
			{#if activeTree}
				<span class="status-cell" class:err={activeTree.status.error}>
					{generating ? 'Generating...' : activeTree.status.message}
				</span>
				{#if activeTree.blockCount > 0}
					<span class="status-cell">{activeTree.blockCount.toLocaleString()} blocks</span>
					<span class="status-cell">{activeTree.genMs} ms</span>
				{/if}
				<span class="status-cell">Ln {cursor.line}, Col {cursor.column}</span>
			{/if}
			<span class="status-cell">{biome.split('_').map((w) => w[0].toUpperCase() + w.slice(1)).join(' ')}</span>
		</div>
	</footer>
</div>

{#if importModalOpen}
	<ImportModal {conn} onImport={importVanillaTree} onClose={() => (importModalOpen = false)} />
{/if}

{#if benchmarkModalOpen && activeTree}
	<BenchmarkModal
		{conn}
		feature={(() => {
			try {
				return JSON.parse(activeTree.treeText)
			} catch {
				return null
			}
		})()}
		onClose={() => (benchmarkModalOpen = false)}
	/>
{/if}

{#if paletteMode}
	<CommandPalette
		commands={paletteMode === 'files' ? fileCommands : commands}
		placeholder={paletteMode === 'files' ? 'Search trees by name...' : 'Type a command...'}
		onClose={() => (paletteMode = null)}
	/>
{/if}

{#if closingDoc}
	<div
		class="modal-backdrop"
		role="button"
		tabindex="0"
		onclick={(e) => {
			if (e.target === e.currentTarget) cancelClose()
		}}
		onkeydown={(e) => e.key === 'Escape' && cancelClose()}
	>
		<div class="modal close-confirm-modal">
			<div class="modal-head">
				<div class="modal-title"><Icon name="alert" size={15} />Unsaved Changes</div>
				<button class="icon-btn" aria-label="Cancel" onclick={cancelClose}><Icon name="close" size={15} /></button>
			</div>
			<div class="modal-body">
				<p class="field-hint">
					<strong class="mono">{docLabel(closingDoc)}</strong> has unsaved changes. Save before closing, or discard them?
				</p>
				{#if !closingDoc.id}
					<div class="control-group">
						<label class="field-label" for="close-confirm-name">Tree Name</label>
						<input
							id="close-confirm-name"
							type="text"
							placeholder="my_custom_tree"
							spellcheck="false"
							disabled={closingSaving}
							bind:value={closingDoc.name}
						/>
					</div>
				{/if}
				{#if closingDoc.status.error}
					<div class="callout error"><Icon name="alert" size={14} /><span>{closingDoc.status.message}</span></div>
				{/if}
			</div>
			<div class="modal-foot">
				<button class="btn secondary" disabled={closingSaving} onclick={cancelClose}>Cancel</button>
				<button class="btn danger" disabled={closingSaving} onclick={discardClose}>Discard</button>
				<button class="btn" disabled={closingSaving} onclick={() => void saveAndClose()}>
					<Icon name={closingSaving ? 'spinner' : 'save'} size={14} class={closingSaving ? 'spin' : ''} />
					{closingSaving ? 'Saving...' : 'Save & Close'}
				</button>
			</div>
		</div>
	</div>
{/if}

<style>
	.workbench {
		display: flex;
		flex-direction: column;
		height: 100%;
		width: 100%;
		min-width: 0;
	}

	.workbench-body {
		flex: 1;
		display: flex;
		min-height: 0;
	}

	/* --- Explorer --- */

	.explorer {
		width: 268px;
		flex-shrink: 0;
		display: flex;
		flex-direction: column;
		background: var(--panel);
		border-right: 1px solid var(--line);
	}

	.panel-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 10px;
		padding: 0 8px 0 14px;
		height: 40px;
		flex-shrink: 0;
		border-bottom: 1px solid var(--line);
	}

	.icon-btn.sm {
		width: 26px;
		height: 26px;
	}

	.pack-root {
		display: flex;
		align-items: center;
		gap: 7px;
		padding: 9px 14px;
		color: var(--text-dim);
		border-bottom: 1px solid var(--line);
		background: rgba(0, 0, 0, 0.15);
	}

	.pack-root :global(svg) {
		color: var(--accent);
	}

	.pack-name {
		flex: 1;
		font-size: 11.5px;
		color: var(--text);
	}

	.pack-tag {
		font-size: 9.5px;
		font-weight: 700;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: var(--text-faint);
		border: 1px solid var(--line);
		border-radius: var(--r-xs);
		padding: 1px 5px;
	}

	.explorer-search {
		padding: 10px 12px 6px;
	}

	.explorer-tree {
		flex: 1;
		overflow-y: auto;
		padding: 4px 8px 12px;
	}

	.group + .group {
		margin-top: 14px;
	}

	.group-head {
		display: flex;
		align-items: center;
		gap: 6px;
		padding: 4px 6px;
		color: var(--text-dim);
	}

	.caret {
		font-size: 9px;
		color: var(--text-faint);
	}

	.group-name {
		flex: 1;
		font-size: 11.5px;
		color: var(--text-dim);
	}

	.group-path {
		font-size: 9.5px;
		color: var(--text-faint);
		padding: 0 6px 5px 21px;
	}

	.group-empty {
		font-size: 11.5px;
		color: var(--text-faint);
		padding: 8px 6px 8px 21px;
	}

	.file-row {
		display: flex;
		align-items: center;
		gap: 8px;
		padding: 5px 8px 5px 21px;
		border-radius: var(--r-xs);
		cursor: pointer;
		color: var(--text-dim);
		font-size: 12px;
		position: relative;
		transition: background var(--fast), color var(--fast);
	}

	.file-row :global(svg) {
		color: var(--text-faint);
		flex-shrink: 0;
	}

	.file-row:hover {
		background: var(--raised);
		color: var(--text);
	}

	.file-row.open {
		color: var(--text);
	}

	.file-row.on {
		background: var(--accent-wash);
		color: var(--text);
		box-shadow: inset 2px 0 0 var(--accent);
	}

	.file-row.on :global(svg) {
		color: var(--accent);
	}

	.file-name {
		flex: 1;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.dot-dirty {
		width: 6px;
		height: 6px;
		border-radius: 50%;
		background: var(--amber);
		flex-shrink: 0;
	}

	.explorer-foot {
		padding: 10px 12px;
		border-top: 1px solid var(--line);
		background: rgba(0, 0, 0, 0.2);
		display: flex;
		flex-direction: column;
		gap: 7px;
		flex-shrink: 0;
	}

	/* --- Main column --- */

	.main {
		flex: 1;
		min-width: 0;
		display: flex;
		flex-direction: column;
	}

	.tabstrip {
		display: flex;
		align-items: stretch;
		height: 36px;
		flex-shrink: 0;
		background: var(--bg-sunken);
		border-bottom: 1px solid var(--line);
		overflow-x: auto;
		overflow-y: hidden;
		scrollbar-width: none;
	}

	.tabstrip::-webkit-scrollbar {
		height: 0;
	}

	.tab {
		display: flex;
		align-items: center;
		gap: 7px;
		padding: 0 8px 0 12px;
		min-width: 118px;
		max-width: 210px;
		border-right: 1px solid var(--line);
		background: transparent;
		color: var(--text-faint);
		font-size: 12px;
		cursor: pointer;
		position: relative;
		flex-shrink: 0;
		transition: background var(--fast), color var(--fast);
	}

	.tab :global(svg) {
		flex-shrink: 0;
	}

	.tab:hover {
		background: var(--raised);
		color: var(--text-dim);
	}

	.tab.on {
		background: var(--panel);
		color: var(--text);
	}

	/* Top accent marks the active document, VS Code style. */
	.tab.on::before {
		content: '';
		position: absolute;
		top: 0;
		left: 0;
		right: 0;
		height: 2px;
		background: var(--accent);
	}

	.tab-label {
		flex: 1;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	/* Dot and close button occupy the same slot: the dot is the resting state for
	   a dirty tab, the close button takes over on hover/active so every tab -
	   dirty or not, saved or brand new - stays closeable. */
	.tab-indicator {
		position: relative;
		width: 18px;
		height: 18px;
		flex-shrink: 0;
		display: grid;
		place-items: center;
	}

	.tab-dirty {
		width: 7px;
		height: 7px;
		border-radius: 50%;
		background: var(--amber);
		transition: opacity var(--fast);
	}

	.tab-close {
		position: absolute;
		inset: 0;
		display: grid;
		place-items: center;
		border-radius: var(--r-xs);
		color: var(--text-faint);
		opacity: 0;
		transition: opacity var(--fast), background var(--fast);
	}

	.tab:hover .tab-close,
	.tab.on .tab-close {
		opacity: 1;
	}

	.tab:hover .tab-dirty,
	.tab.on .tab-dirty {
		opacity: 0;
	}

	.tab-close:hover {
		background: var(--line-strong);
		color: var(--text);
	}

	/* --- Document bar --- */

	.doc-bar {
		display: flex;
		align-items: center;
		gap: 8px;
		height: 40px;
		flex-shrink: 0;
		padding: 0 10px 0 14px;
		background: var(--panel);
		border-bottom: 1px solid var(--line);
	}

	.breadcrumb {
		display: flex;
		align-items: center;
		gap: 4px;
		font-size: 10.5px;
		color: var(--text-faint);
		white-space: nowrap;
		overflow: hidden;
	}

	.crumb-sep {
		color: var(--line-strong);
	}

	.name-field {
		display: flex;
		align-items: center;
		flex: 1;
		min-width: 0;
		max-width: 260px;
	}

	.name-field input {
		width: 100%;
		background: transparent;
		border: 1px solid transparent;
		padding: 4px 6px;
		font-family: var(--font-mono);
		font-size: 11.5px;
		color: var(--text);
		border-radius: var(--r-xs);
	}

	.name-field input:hover {
		border-color: var(--line);
	}

	.name-field input:focus {
		background: var(--bg-sunken);
		border-color: var(--accent-line);
		box-shadow: none;
	}

	.name-ext {
		color: var(--text-faint);
		font-size: 11.5px;
		margin-left: -4px;
		pointer-events: none;
	}

	.doc-actions {
		display: flex;
		align-items: center;
		gap: 3px;
		margin-left: auto;
		flex-shrink: 0;
	}

	.bar-sep {
		width: 1px;
		height: 18px;
		background: var(--line);
		margin: 0 4px;
	}

	/* --- Editor / preview split --- */

	.split-body {
		flex: 1;
		display: flex;
		min-height: 0;
	}

	.editor-pane {
		flex-grow: 0;
		flex-shrink: 0;
		display: flex;
		flex-direction: column;
		min-width: 0;
		background: var(--bg-sunken);
		overflow: hidden;
		transition: flex-basis var(--med);
	}

	.editor-pane.collapsed {
		transition: none;
	}

	.editor-tabs {
		display: flex;
		align-items: center;
		border-bottom: 1px solid var(--line);
		background: var(--panel);
		flex-shrink: 0;
	}

	.tabs {
		display: flex;
		gap: 2px;
		padding: 6px;
	}

	.tabs button {
		position: relative;
		background: none;
		border: none;
		color: var(--text-faint);
		padding: 7px 12px;
		font-family: var(--font-mono);
		font-size: 11.5px;
		border-radius: var(--r-sm);
		cursor: pointer;
		transition: color var(--fast), background var(--fast);
	}

	.tabs button:hover {
		color: var(--text-dim);
		background: var(--raised);
	}

	.tabs button.active {
		color: var(--text);
		background: var(--raised-hi);
	}

	.tabs button.active::after {
		content: '';
		position: absolute;
		left: 12px;
		right: 12px;
		bottom: 3px;
		height: 2px;
		border-radius: 2px;
		background: var(--accent);
	}

	.editor-host {
		flex: 1;
		min-height: 0;
	}

	.vsplit-handle {
		flex: 0 0 7px;
		cursor: col-resize;
		position: relative;
		background: var(--bg-sunken);
		border-left: 1px solid var(--line);
		border-right: 1px solid var(--line);
		z-index: 1;
	}

	.vsplit-handle::after {
		content: '';
		position: absolute;
		top: 50%;
		left: 50%;
		transform: translate(-50%, -50%);
		width: 3px;
		height: 36px;
		border-radius: 2px;
		background: var(--line-strong);
		transition: background var(--fast), height var(--med);
	}

	.vsplit-handle:hover::after,
	.vsplit-handle:active::after {
		background: var(--accent);
		height: 56px;
	}

	.preview-pane {
		flex: 1 1 auto;
		min-width: 0;
		position: relative;
		background: #0b0f0c;
	}

	.canvas-layer {
		position: absolute;
		inset: 0;
		background: #0b0f0c;
	}

	.canvas-layer canvas {
		position: absolute;
		inset: 0;
		width: 100%;
		height: 100%;
		touch-action: none;
	}

	.viewport-vignette {
		position: absolute;
		inset: 0;
		pointer-events: none;
		background: radial-gradient(75% 65% at 50% 42%, transparent 40%, rgba(0, 0, 0, 0.42));
	}

	.overlay-panel {
		position: absolute;
		inset: 0;
		background: var(--bg);
		display: flex;
		z-index: 3;
		overflow: hidden;
	}

	/* --- Welcome --- */

	.welcome {
		align-items: center;
		justify-content: center;
	}

	.welcome-inner {
		display: flex;
		flex-direction: column;
		align-items: center;
		gap: 12px;
		text-align: center;
		max-width: 380px;
		padding: 24px;
	}

	.welcome-mark {
		display: grid;
		place-items: center;
		width: 60px;
		height: 60px;
		border-radius: var(--r-lg);
		color: var(--accent);
		background: var(--accent-wash);
		border: 1px solid var(--accent-line);
		box-shadow: 0 0 40px -10px rgba(139, 197, 63, 0.5);
	}

	.welcome h2 {
		font-size: 18px;
	}

	.welcome p {
		margin: 0;
		color: var(--text-dim);
		font-size: 12.5px;
	}

	.welcome-actions {
		display: flex;
		gap: 8px;
		margin-top: 4px;
	}

	.shortcuts {
		margin: 14px 0 0;
		width: 100%;
		display: flex;
		flex-direction: column;
		gap: 7px;
		border-top: 1px solid var(--line);
		padding-top: 14px;
	}

	.shortcuts div {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 12px;
	}

	.shortcuts dt {
		font-size: 11.5px;
		color: var(--text-faint);
	}

	.shortcuts dd {
		margin: 0;
		display: flex;
		gap: 3px;
	}

	kbd {
		font-family: var(--font-mono);
		font-size: 10px;
		color: var(--text-dim);
		background: var(--panel);
		border: 1px solid var(--line-strong);
		border-bottom-width: 2px;
		border-radius: var(--r-xs);
		padding: 2px 5px;
	}

	/* --- Viewport chrome --- */

	.viewport-toolbar {
		position: absolute;
		top: 12px;
		right: 12px;
		z-index: 2;
	}

	.tool-group {
		display: flex;
		align-items: center;
		gap: 2px;
		padding: 3px;
		border-radius: var(--r-md);
		background: rgba(16, 20, 17, 0.72);
		border: 1px solid rgba(255, 255, 255, 0.07);
		backdrop-filter: blur(10px);
		-webkit-backdrop-filter: blur(10px);
		box-shadow: var(--shadow-md), var(--inset-hi);
	}

	.tool-sep {
		width: 1px;
		align-self: stretch;
		background: rgba(255, 255, 255, 0.08);
		margin: 2px 3px;
	}

	.biome-select {
		width: auto;
		max-width: 150px;
		background: transparent;
		border: none;
		color: var(--text-dim);
		font-size: 11.5px;
		padding: 5px 24px 5px 8px;
		background-position: right 4px center;
	}

	.coord-field {
		display: flex;
		align-items: center;
		gap: 4px;
		padding: 0 4px;
	}

	.coord-label {
		font-size: 10.5px;
		color: var(--text-faint);
		text-transform: uppercase;
		letter-spacing: 0.08em;
	}

	.coord-field input {
		width: 52px;
		background: transparent;
		border: none;
		border-bottom: 1px solid transparent;
		color: var(--text-dim);
		font-family: var(--font-mono);
		font-size: 11.5px;
		padding: 4px 2px;
		text-align: right;
	}

	.coord-field input:hover {
		border-bottom-color: var(--line);
	}

	.coord-field input:focus {
		outline: none;
		color: var(--text);
		border-bottom-color: var(--accent-line);
	}

	/* Sits under the toolbar rather than inside it: this is a readout of what
	   was generated, not another control to reach for. */
	.world-readout {
		position: absolute;
		top: 52px;
		left: 50%;
		transform: translateX(-50%);
		font-size: 10.5px;
		color: var(--text-faint);
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-sm, 4px);
		padding: 3px 9px;
		pointer-events: none;
		white-space: nowrap;
	}

	.biome-select:hover {
		border: none;
		color: var(--text);
		background-color: rgba(255, 255, 255, 0.06);
	}

	.biome-select:focus {
		box-shadow: none;
		border: none;
		background-color: rgba(255, 255, 255, 0.06);
	}

	.tool-btn {
		display: grid;
		place-items: center;
		width: 30px;
		height: 28px;
		padding: 0;
		border: none;
		border-radius: var(--r-sm);
		background: transparent;
		color: var(--text-dim);
		cursor: pointer;
		position: relative;
		transition: background var(--fast), color var(--fast);
	}

	.tool-btn:hover {
		background: rgba(255, 255, 255, 0.06);
		color: var(--text);
	}

	.tool-btn.active {
		background: var(--accent-wash);
		color: var(--accent-hi);
		box-shadow: inset 0 0 0 1px var(--accent-line);
	}

	.tool-btn:focus-visible {
		outline: none;
		box-shadow: var(--ring);
	}

	.swatch {
		overflow: hidden;
	}

	.swatch-chip {
		width: 15px;
		height: 15px;
		border-radius: var(--r-xs);
		border: 1px solid rgba(255, 255, 255, 0.2);
		display: block;
	}

	.swatch input[type='color'] {
		position: absolute;
		inset: 0;
		opacity: 0;
		cursor: pointer;
		padding: 0;
		border: none;
	}

	/* Only hard failures float over the viewport; everything else is the status bar. */
	.floating-error {
		position: absolute;
		left: 12px;
		bottom: 12px;
		max-width: min(600px, calc(100% - 24px));
		z-index: 2;
		display: flex;
		align-items: flex-start;
		gap: 9px;
		padding: 9px 12px;
		border-radius: var(--r-md);
		background: rgba(16, 20, 17, 0.82);
		border: 1px solid rgba(226, 102, 74, 0.4);
		backdrop-filter: blur(10px);
		-webkit-backdrop-filter: blur(10px);
		box-shadow: var(--shadow-md);
		color: var(--danger);
		font-size: 12px;
	}

	.floating-error :global(svg) {
		margin-top: 1px;
		flex-shrink: 0;
	}

	.error-details {
		margin-top: 5px;
		padding-top: 5px;
		border-top: 1px solid rgba(226, 102, 74, 0.25);
		color: var(--text-faint);
		font-size: 11px;
		white-space: pre-wrap;
		word-break: break-word;
		max-height: 120px;
		overflow-y: auto;
	}

	/* --- Status bar --- */

	.statusbar {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 16px;
		height: 24px;
		flex-shrink: 0;
		padding: 0 12px;
		background: var(--panel);
		border-top: 1px solid var(--line);
		font-family: var(--font-mono);
		font-size: 10.5px;
		color: var(--text-faint);
		overflow: hidden;
	}

	.status-left,
	.status-right {
		display: flex;
		align-items: center;
		gap: 14px;
		min-width: 0;
	}

	.status-cell {
		display: flex;
		align-items: center;
		gap: 5px;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.status-cell.accent {
		color: var(--accent);
	}

	.status-cell.warn {
		color: var(--amber);
	}

	.status-cell.err {
		color: var(--danger);
	}

	.live-dot {
		width: 5px;
		height: 5px;
		border-radius: 50%;
		background: currentColor;
		animation: pulse-dot 2s var(--ease) infinite;
	}

	/* --- Close-confirmation modal --- */

	.close-confirm-modal {
		width: min(420px, 92vw);
	}

	.close-confirm-modal .control-group {
		display: flex;
		flex-direction: column;
		gap: 7px;
	}

	.close-confirm-modal strong {
		color: var(--text);
		font-weight: 500;
	}
</style>
