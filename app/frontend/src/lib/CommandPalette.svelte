<script lang="ts" module>
	export interface Command {
		id: string
		label: string
		hint?: string
		icon?: string
		keybinding?: string
		run: () => void
	}
</script>

<script lang="ts">
	// Ctrl+Shift+P command list / Ctrl+P quick-open, sharing one overlay. Matching
	// is a plain subsequence test scored so that consecutive hits and word-start
	// hits rank first - good enough for a few dozen entries, no dependency.
	import Icon from './Icon.svelte'

	let {
		commands,
		placeholder = 'Type a command...',
		onClose,
	}: { commands: Command[]; placeholder?: string; onClose: () => void } = $props()

	let query = $state('')
	let selected = $state(0)
	let inputEl = $state<HTMLInputElement | undefined>(undefined)
	let listEl = $state<HTMLDivElement | undefined>(undefined)

	function score(text: string, q: string): number {
		if (!q) return 1
		const t = text.toLowerCase()
		let ti = 0
		let total = 0
		let streak = 0
		for (const ch of q.toLowerCase()) {
			const found = t.indexOf(ch, ti)
			if (found === -1) return 0
			streak = found === ti && ti > 0 ? streak + 1 : 0
			// Word starts are worth more than mid-word hits.
			const boundary = found === 0 || t[found - 1] === ' ' || t[found - 1] === ':' ? 3 : 0
			total += 1 + streak + boundary
			ti = found + 1
		}
		return total
	}

	const results = $derived(
		commands
			.map((c) => ({ c, s: score(c.label + ' ' + (c.hint ?? ''), query) }))
			.filter((r) => r.s > 0)
			.sort((a, b) => b.s - a.s)
			.slice(0, 40)
			.map((r) => r.c),
	)

	// Any change to the result set invalidates the old highlight index.
	$effect(() => {
		results.length
		selected = 0
	})

	$effect(() => {
		inputEl?.focus()
	})

	function scrollSelectedIntoView(): void {
		queueMicrotask(() => listEl?.querySelector('.row.on')?.scrollIntoView({ block: 'nearest' }))
	}

	function run(c: Command | undefined): void {
		if (!c) return
		onClose()
		c.run()
	}

	function onKey(e: KeyboardEvent): void {
		if (e.key === 'ArrowDown') {
			e.preventDefault()
			selected = Math.min(selected + 1, results.length - 1)
			scrollSelectedIntoView()
		} else if (e.key === 'ArrowUp') {
			e.preventDefault()
			selected = Math.max(selected - 1, 0)
			scrollSelectedIntoView()
		} else if (e.key === 'Enter') {
			e.preventDefault()
			run(results[selected])
		} else if (e.key === 'Escape') {
			e.preventDefault()
			onClose()
		}
	}
</script>

<div
	class="palette-backdrop"
	role="button"
	tabindex="-1"
	onclick={(e) => {
		if (e.target === e.currentTarget) onClose()
	}}
	onkeydown={(e) => e.key === 'Escape' && onClose()}
>
	<div class="palette">
		<div class="palette-input">
			<Icon name="search" size={15} />
			<!-- svelte-ignore a11y_autofocus -->
			<input bind:this={inputEl} bind:value={query} {placeholder} onkeydown={onKey} spellcheck="false" />
		</div>
		<div class="palette-list" bind:this={listEl}>
			{#each results as c, i (c.id)}
				<div
					class="row"
					class:on={i === selected}
					role="button"
					tabindex="-1"
					onmousemove={() => (selected = i)}
					onclick={() => run(c)}
					onkeydown={(e) => e.key === 'Enter' && run(c)}
				>
					<span class="row-icon">{#if c.icon}<Icon name={c.icon} size={14} />{/if}</span>
					<span class="row-label">{c.label}</span>
					{#if c.hint}<span class="row-hint">{c.hint}</span>{/if}
					{#if c.keybinding}<kbd>{c.keybinding}</kbd>{/if}
				</div>
			{:else}
				<div class="row empty">No matching commands</div>
			{/each}
		</div>
	</div>
</div>

<style>
	.palette-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(5, 7, 6, 0.5);
		backdrop-filter: blur(3px);
		-webkit-backdrop-filter: blur(3px);
		display: flex;
		justify-content: center;
		align-items: flex-start;
		padding-top: 12vh;
		z-index: 3000;
		animation: fade-in 100ms var(--ease);
	}

	.palette {
		width: min(620px, 92vw);
		background: var(--panel);
		border: 1px solid var(--line-strong);
		border-radius: var(--r-lg);
		box-shadow: var(--shadow-lg);
		overflow: hidden;
		display: flex;
		flex-direction: column;
		max-height: 62vh;
		animation: modal-in 140ms var(--ease);
	}

	.palette-input {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 0 14px;
		border-bottom: 1px solid var(--line);
		flex-shrink: 0;
	}

	.palette-input :global(svg) {
		color: var(--text-faint);
	}

	.palette-input input {
		flex: 1;
		background: none;
		border: none;
		outline: none;
		box-shadow: none;
		color: var(--text);
		font-size: 14px;
		padding: 15px 0;
	}

	.palette-input input:focus {
		background: none;
		box-shadow: none;
		border: none;
	}

	.palette-list {
		overflow-y: auto;
		padding: 6px;
		min-height: 0;
	}

	.row {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 8px 10px;
		border-radius: var(--r-sm);
		font-size: 12.5px;
		color: var(--text-dim);
		cursor: pointer;
	}

	.row.on {
		background: var(--accent-wash);
		color: var(--text);
		box-shadow: inset 0 0 0 1px var(--accent-line);
	}

	.row.empty {
		color: var(--text-faint);
		justify-content: center;
		padding: 20px;
		cursor: default;
	}

	.row-icon {
		width: 16px;
		display: grid;
		place-items: center;
		color: var(--text-faint);
		flex-shrink: 0;
	}

	.row.on .row-icon {
		color: var(--accent);
	}

	.row-label {
		flex: 1;
		white-space: nowrap;
		overflow: hidden;
		text-overflow: ellipsis;
	}

	.row-hint {
		font-family: var(--font-mono);
		font-size: 10.5px;
		color: var(--text-faint);
		flex-shrink: 0;
	}

	kbd {
		font-family: var(--font-mono);
		font-size: 10px;
		color: var(--text-faint);
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-bottom-width: 2px;
		border-radius: var(--r-xs);
		padding: 2px 5px;
		flex-shrink: 0;
	}
</style>
