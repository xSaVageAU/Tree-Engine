<script lang="ts">
	// Thin wrapper around Monaco's AMD bundle (served locally from /monaco-vs/,
	// see vite.config.js's viteStaticCopy - no CDN dependency, since this is a
	// desktop app that should work offline once set up).
	//
	// `value` is the source of truth from the parent; `onChange` fires on every
	// edit (debounced upstream by the caller if needed). Programmatic value
	// updates (switching tabs, loading a different tree) must go through the
	// `value` prop, not user typing - `isProgrammaticUpdate` distinguishes the
	// two so we don't fire onChange for updates we made ourselves.
	import { onDestroy, onMount } from 'svelte'

	let { value, onChange }: { value: string; onChange: (text: string) => void } = $props()

	let containerEl = $state<HTMLDivElement | undefined>(undefined)
	let editor: any
	let monacoRef: any
	let isProgrammaticUpdate = false

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

	onMount(() => {
		let cancelled = false
		loadMonaco().then((monaco) => {
			if (cancelled || !containerEl) return
			monacoRef = monaco
			editor = monaco.editor.create(containerEl, {
				value,
				language: 'json',
				theme: 'vs-dark',
				automaticLayout: true,
				minimap: { enabled: false },
				fontSize: 13,
				tabSize: 2,
				insertSpaces: true,
			})
			editor.onDidChangeModelContent(() => {
				if (isProgrammaticUpdate) return
				onChange(editor.getValue())
			})
		})
		return () => {
			cancelled = true
		}
	})

	onDestroy(() => {
		editor?.dispose()
	})

	// Reflect external value changes (e.g. switching tabs) into the editor
	// without re-triggering onChange.
	$effect(() => {
		const text = value
		if (editor && editor.getValue() !== text) {
			isProgrammaticUpdate = true
			editor.setValue(text)
			isProgrammaticUpdate = false
		}
	})
</script>

<div class="monaco-container" bind:this={containerEl}></div>

<style>
	.monaco-container {
		width: 100%;
		height: 100%;
	}
</style>
