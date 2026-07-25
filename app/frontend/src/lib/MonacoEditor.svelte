<script lang="ts" module>
	// One Monaco ITextModel per document URI, cached at module scope.
	//
	// This is what makes tabs work: switching documents swaps the editor's model
	// instead of rewriting its text. `editor.setValue()` resets the model's undo
	// stack and cursor, so the previous single-model approach silently threw away
	// undo history every time you flipped between Tree Config and Placement.
	const models = new Map<string, any>()

	// Called by the parent when a tab closes, so a reopened document starts from
	// the file on disk rather than a stale buffer.
	export function disposeDocModels(prefix: string): void {
		for (const [uri, model] of [...models]) {
			if (uri.startsWith(prefix)) {
				model.dispose()
				models.delete(uri)
			}
		}
	}

	// Writes text into an existing buffer as an undoable edit. Returns false when
	// the document has never been opened (no model yet) - the caller keeps the
	// text itself and the model picks it up when it is first created.
	export function setModelText(uri: string, text: string): boolean {
		const model = models.get(uri)
		if (!model) return false
		if (model.getValue() === text) return true
		model.pushEditOperations([], [{ range: model.getFullModelRange(), text }], () => null)
		return true
	}
</script>

<script lang="ts">
	// Thin wrapper around Monaco's AMD bundle (served locally from /monaco-vs/,
	// see vite.config.js's viteStaticCopy - no CDN dependency, since this is a
	// desktop app that should work offline once set up).
	//
	// The parent owns document text: `value` seeds a model the first time a URI is
	// seen and is never pushed again, so the editor cannot clobber what the user
	// is typing. Edits flow out through `onChange` only.
	import { onDestroy, onMount, untrack } from 'svelte'

	let {
		uri,
		value,
		onChange,
		onCursor,
		onSave,
		onPalette,
		onQuickOpen,
	}: {
		uri: string
		value: string
		onChange: (text: string) => void
		onCursor?: (line: number, column: number) => void
		onSave?: () => void
		onPalette?: () => void
		onQuickOpen?: () => void
	} = $props()

	let containerEl = $state<HTMLDivElement | undefined>(undefined)
	let editor: any
	let monacoRef: any
	let currentUri = ''
	const viewStates = new Map<string, any>()

	function loadMonaco(): Promise<any> {
		return new Promise((resolve, reject) => {
			if ((window as any).monaco) {
				resolve((window as any).monaco)
				return
			}
			const existing = document.getElementById('monaco-loader-script')
			const onLoaderReady = () => {
				;(window as any).require.config({ paths: { vs: '/monaco-vs' } })
				;(window as any).require(['vs/editor/editor.main'], () => resolve((window as any).monaco), reject)
			}
			if (existing) {
				onLoaderReady()
				return
			}
			const script = document.createElement('script')
			script.id = 'monaco-loader-script'
			script.src = '/monaco-vs/loader.js'
			script.onload = onLoaderReady
			script.onerror = reject
			document.head.appendChild(script)
		})
	}

	// Monaco ships with `vs-dark`, which clashes with the app's palette. Define a
	// matching theme once per session so the JSON panel reads as part of the UI
	// rather than an embedded VS Code.
	function defineTheme(monaco: any): void {
		monaco.editor.defineTheme('tree-engine', {
			base: 'vs-dark',
			inherit: true,
			rules: [
				{ token: '', foreground: 'e9eee7', background: '090b0a' },
				{ token: 'string.key.json', foreground: '98a597' },
				{ token: 'string.value.json', foreground: 'a4dc5b' },
				{ token: 'number', foreground: 'e3a83f' },
				{ token: 'keyword.json', foreground: 'e2664a' },
				{ token: 'delimiter', foreground: '6a766b' },
				{ token: 'comment', foreground: '4d5a4e', fontStyle: 'italic' },
			],
			colors: {
				'editor.background': '#090b0a',
				'editor.foreground': '#e9eee7',
				'editorLineNumber.foreground': '#39423a',
				'editorLineNumber.activeForeground': '#8bc53f',
				'editor.lineHighlightBackground': '#12160f',
				'editor.selectionBackground': '#2c3d1c',
				'editor.inactiveSelectionBackground': '#1c2117',
				'editorCursor.foreground': '#8bc53f',
				'editorIndentGuide.background1': '#1c211d',
				'editorIndentGuide.activeBackground1': '#333a33',
				'editorWidget.background': '#151916',
				'editorWidget.border': '#333a33',
				'editorGutter.background': '#090b0a',
				'editorBracketMatch.background': '#8bc53f22',
				'editorBracketMatch.border': '#8bc53f66',
				'scrollbarSlider.background': '#333a3399',
				'scrollbarSlider.hoverBackground': '#46503fcc',
				'scrollbarSlider.activeBackground': '#8bc53f66',
			},
		})
	}

	function modelFor(targetUri: string, initial: string): any {
		let model = models.get(targetUri)
		if (!model) {
			model = monacoRef.editor.createModel(initial, 'json', monacoRef.Uri.parse(targetUri))
			models.set(targetUri, model)
		}
		return model
	}

	// Swaps the visible buffer, preserving each document's scroll/cursor state.
	function applyUri(targetUri: string, seed: string): void {
		if (!editor || !monacoRef || targetUri === currentUri) return
		if (currentUri) viewStates.set(currentUri, editor.saveViewState())
		editor.setModel(modelFor(targetUri, seed))
		const restored = viewStates.get(targetUri)
		if (restored) editor.restoreViewState(restored)
		currentUri = targetUri
	}

	onMount(() => {
		let cancelled = false
		loadMonaco().then((monaco) => {
			if (cancelled || !containerEl) return
			monacoRef = monaco
			defineTheme(monaco)
			editor = monaco.editor.create(containerEl, {
				model: null,
				language: 'json',
				theme: 'tree-engine',
				automaticLayout: true,
				minimap: { enabled: false },
				fontSize: 12.5,
				fontFamily: '"Cascadia Mono", "Cascadia Code", "JetBrains Mono", Consolas, monospace',
				fontLigatures: false,
				lineHeight: 1.65,
				padding: { top: 12, bottom: 12 },
				renderLineHighlight: 'gutter',
				smoothScrolling: true,
				scrollBeyondLastLine: false,
				scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },
				tabSize: 2,
				insertSpaces: true,
			})

			applyUri(uri, value)

			editor.onDidChangeModelContent(() => onChange(editor.getValue()))
			editor.onDidChangeCursorPosition((e: any) => onCursor?.(e.position.lineNumber, e.position.column))

			// Monaco swallows keydown inside the editor, so app shortcuts have to be
			// registered with it directly or they die when the editor has focus.
			const { KeyMod, KeyCode } = monaco
			editor.addCommand(KeyMod.CtrlCmd | KeyCode.KeyS, () => onSave?.())
			editor.addCommand(KeyMod.CtrlCmd | KeyMod.Shift | KeyCode.KeyP, () => onPalette?.())
			editor.addCommand(KeyMod.CtrlCmd | KeyCode.KeyP, () => onQuickOpen?.())
		})
		return () => {
			cancelled = true
		}
	})

	onDestroy(() => {
		if (currentUri && editor) viewStates.set(currentUri, editor.saveViewState())
		editor?.dispose()
	})

	// React to document switches only. `value` is read untracked because it is a
	// seed, not a binding - reacting to it would re-introduce the clobbering bug.
	$effect(() => {
		const next = uri
		if (editor) untrack(() => applyUri(next, value))
	})
</script>

<div class="monaco-container" bind:this={containerEl}></div>

<style>
	.monaco-container {
		width: 100%;
		height: 100%;
	}
</style>
