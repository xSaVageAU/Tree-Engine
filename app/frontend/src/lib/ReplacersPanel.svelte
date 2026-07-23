<script lang="ts">
	// Tree Replacer management: lets vanilla trees (oak, birch, ...) be
	// substituted at world-gen time with a pool of custom trees, either a
	// single weighted pool (one default + chance-based alternatives) or a
	// simple equal-chance pool. Mirrors the original mod's TreeReplacerUI 1:1,
	// just rebuilt with reactive state instead of manual DOM manipulation.
	import { onMount } from 'svelte'
	import {
		deleteReplacer,
		hotReload,
		listVanillaTrees,
		listReplacers,
		saveReplacer,
		type ModConnection,
		type Replacer,
		type ReplacerAlternative,
	} from '../renderer/mod-client'

	let { conn, customTrees, onBack }: { conn: ModConnection; customTrees: string[]; onBack: () => void } = $props()

	let replacers = $state<Replacer[]>([])
	let vanillaTrees = $state<string[]>([])
	let view = $state<'list' | 'form'>('list')
	let statusMessage = $state('')

	// Form state
	let editingId = $state<string | null>(null)
	let formType = $state<'WEIGHTED' | 'SIMPLE'>('WEIGHTED')
	let formVanillaTreeId = $state('')
	let formDefaultTree = $state('')
	let formAlternatives = $state<ReplacerAlternative[]>([])
	let formPool = $state<string[]>([])

	onMount(() => {
		Promise.all([listReplacers(conn), listVanillaTrees(conn)])
			.then(([r, v]) => {
				replacers = r
				vanillaTrees = v
			})
			.catch((e) => console.error('Failed to load replacer data', e))
	})

	function displayName(treeId: string): string {
		const name = treeId.split(':').pop() ?? treeId
		return name.split('_').map((w) => w.charAt(0).toUpperCase() + w.slice(1)).join(' ')
	}

	function showCreateForm(): void {
		editingId = null
		formType = 'WEIGHTED'
		formVanillaTreeId = ''
		formDefaultTree = ''
		formAlternatives = []
		formPool = []
		view = 'form'
	}

	function showEditForm(r: Replacer): void {
		editingId = r.id
		formType = r.type ?? 'WEIGHTED'
		formVanillaTreeId = r.vanilla_tree_id
		formDefaultTree = r.default_tree ?? ''
		formAlternatives = r.alternatives ? [...r.alternatives] : []
		formPool = r.features ? [...r.features] : []
		view = 'form'
	}

	function addAlternative(): void {
		formAlternatives = [...formAlternatives, { feature: '', chance: 0.1 }]
	}
	function removeAlternative(i: number): void {
		formAlternatives = formAlternatives.filter((_, idx) => idx !== i)
	}
	function addPoolEntry(): void {
		formPool = [...formPool, '']
	}
	function removePoolEntry(i: number): void {
		formPool = formPool.filter((_, idx) => idx !== i)
	}

	async function save(): Promise<void> {
		if (!formVanillaTreeId) {
			alert('Please select a vanilla tree to replace')
			return
		}

		const replacer: Replacer = {
			id: editingId ?? '',
			vanilla_tree_id: formVanillaTreeId,
			type: formType,
		}

		if (formType === 'WEIGHTED') {
			if (!formDefaultTree) {
				alert('Please select a default tree')
				return
			}
			const alternatives = formAlternatives.filter((a) => a.feature && a.chance > 0 && a.chance <= 1)
			const total = alternatives.reduce((sum, a) => sum + a.chance, 0)
			if (total > 1.0) {
				alert('Total chance cannot exceed 1.0. Current: ' + total.toFixed(2))
				return
			}
			replacer.default_tree = formDefaultTree
			replacer.alternatives = alternatives
		} else {
			const features = formPool.filter((f) => f)
			if (features.length === 0) {
				alert('Please add at least one tree to the pool')
				return
			}
			replacer.features = features
		}

		try {
			await saveReplacer(conn, replacer)
			replacers = await listReplacers(conn)
			view = 'list'
			try {
				await hotReload(conn)
				statusMessage = 'Tree replacer saved and hot-reloaded!'
			} catch {
				statusMessage = 'Saved! (hot reload failed)'
			}
			setTimeout(() => (statusMessage = ''), 3000)
		} catch (e) {
			alert('Failed to save tree replacer: ' + (e as Error).message)
		}
	}

	async function remove(id: string): Promise<void> {
		if (!confirm('Are you sure you want to delete this tree replacer? This will restore the vanilla tree.')) return
		try {
			await deleteReplacer(conn, id)
			replacers = await listReplacers(conn)
			statusMessage = 'Tree replacer deleted.'
			setTimeout(() => (statusMessage = ''), 3000)
		} catch (e) {
			alert('Failed to delete tree replacer: ' + (e as Error).message)
		}
	}
</script>

<div class="replacers-panel">
	<div class="settings-header">
		<button class="back-btn" onclick={onBack}>← Back</button>
		<h2>Tree Replacers</h2>
	</div>

	{#if statusMessage}
		<div class="status-banner">{statusMessage}</div>
	{/if}

	<div class="scroll-content">
		{#if view === 'list'}
			<button class="btn full" onclick={showCreateForm}>+ Create New Replacer</button>
			{#if replacers.length === 0}
				<p class="empty">No tree replacers yet. Create one to get started!</p>
			{:else}
				{#each replacers as r (r.id)}
					<div class="replacer-item">
						<h4>{displayName(r.vanilla_tree_id)}</h4>
						<p class="dim">{r.vanilla_tree_id}</p>
						<p class="dim">Type: {r.type === 'SIMPLE' ? 'Simple' : 'Weighted'}</p>
						<div class="row">
							<button class="btn secondary btn-sm" onclick={() => showEditForm(r)}>Edit</button>
							<button class="btn secondary btn-sm" onclick={() => remove(r.id)}>Delete</button>
						</div>
						{#if r.type === 'SIMPLE'}
							<p class="details"><strong>Pool:</strong> {(r.features ?? []).map((f) => f.split(':').pop()).join(', ') || 'empty'}</p>
						{:else}
							<p class="details"><strong>Default:</strong> {r.default_tree ?? 'not set'}</p>
							<p class="details"><strong>Alternatives:</strong> {(r.alternatives ?? []).map((a) => `${a.feature.split(':').pop()} (${(a.chance * 100).toFixed(0)}%)`).join(', ') || 'none'}</p>
						{/if}
					</div>
				{/each}
			{/if}
		{:else}
			<h3>{editingId ? 'Edit' : 'Create'} Tree Replacer</h3>

			<div class="control-group">
				<label for="replacer-type">Replacer Type</label>
				<select id="replacer-type" bind:value={formType}>
					<option value="WEIGHTED">Weighted (Default + Chances)</option>
					<option value="SIMPLE">Simple (Equal Chance Pool)</option>
				</select>
			</div>

			<div class="control-group">
				<label for="replacer-vanilla">Vanilla Tree to Replace</label>
				<select id="replacer-vanilla" bind:value={formVanillaTreeId} disabled={!!editingId}>
					<option value="">Select a vanilla tree...</option>
					{#each vanillaTrees as id (id)}
						<option value={id}>{displayName(id)} ({id})</option>
					{/each}
				</select>
				{#if editingId}
					<p class="hint">Cannot change vanilla tree for an existing replacer</p>
				{/if}
			</div>

			{#if formType === 'WEIGHTED'}
				<div class="control-group">
					<label for="default-tree">Default Tree</label>
					<p class="hint">The fallback tree (used when no alternative is selected)</p>
					<select id="default-tree" bind:value={formDefaultTree}>
						<option value="">Select default tree...</option>
						{#each customTrees as tree (tree)}
							<option value={`tree_engine:${tree}`}>{tree}</option>
						{/each}
					</select>
				</div>

				<div class="control-group">
					<label for="alternatives">Weighted Alternatives</label>
					<p class="hint">Trees that can replace the default with a specific probability</p>
					<div class="entry-list" id="alternatives">
						{#if customTrees.length === 0}
							<p class="empty">No custom trees available. Create some trees first!</p>
						{:else}
							{#each formAlternatives as alt, i}
								<div class="entry-row">
									<select bind:value={alt.feature}>
										<option value="">Select tree...</option>
										{#each customTrees as tree (tree)}
											<option value={`tree_engine:${tree}`}>{tree}</option>
										{/each}
									</select>
									<input type="number" min="0" max="1" step="0.01" bind:value={alt.chance} />
									<button class="remove-btn" onclick={() => removeAlternative(i)}>✕</button>
								</div>
							{/each}
						{/if}
					</div>
					<button class="btn secondary full" onclick={addAlternative}>+ Add Alternative</button>
					<p class="hint">💡 Chance is the probability this tree replaces the default (0.0-1.0)</p>
				</div>
			{:else}
				<div class="control-group">
					<label for="pool">Tree Pool</label>
					<p class="hint">All trees in this pool have an equal chance of being picked</p>
					<div class="entry-list" id="pool">
						{#each formPool as _, i}
							<div class="entry-row">
								<select bind:value={formPool[i]}>
									<option value="">Select tree...</option>
									{#each customTrees as tree (tree)}
										<option value={`tree_engine:${tree}`}>{tree}</option>
									{/each}
								</select>
								<button class="remove-btn" onclick={() => removePoolEntry(i)}>✕</button>
							</div>
						{/each}
					</div>
					<button class="btn secondary full" onclick={addPoolEntry}>+ Add Tree to Pool</button>
				</div>
			{/if}

			<div class="row">
				<button class="btn" onclick={save}>Save Replacer</button>
				<button class="btn secondary" onclick={() => (view = 'list')}>Cancel</button>
			</div>
		{/if}
	</div>
</div>

<style>
	.replacers-panel {
		width: 100%;
		display: flex;
		flex-direction: column;
		background: var(--panel);
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
	}
	.status-banner {
		background: rgba(78, 201, 176, 0.15);
		color: var(--accent);
		padding: 8px 15px;
		font-size: 13px;
	}
	.scroll-content {
		flex: 1;
		overflow-y: auto;
		padding: 15px;
		display: flex;
		flex-direction: column;
		gap: 10px;
	}
	.btn.full {
		width: 100%;
	}
	.empty {
		padding: 20px;
		text-align: center;
		color: var(--text-dim);
		font-size: 13px;
	}
	.replacer-item {
		background: var(--bg);
		border: 1px solid var(--panel-border);
		border-radius: 6px;
		padding: 12px;
	}
	.replacer-item h4 {
		margin: 0 0 4px;
	}
	.dim {
		margin: 0;
		color: var(--text-dim);
		font-size: 12px;
	}
	.details {
		margin: 6px 0 0;
		font-size: 12px;
		color: var(--text-dim);
	}
	.row {
		display: flex;
		gap: 8px;
		margin: 8px 0;
	}
	.control-group {
		display: flex;
		flex-direction: column;
		gap: 6px;
		margin-bottom: 8px;
	}
	.control-group label {
		font-size: 12px;
		color: var(--text-dim);
	}
	select,
	input[type='number'] {
		background: var(--bg);
		border: 1px solid var(--panel-border);
		color: var(--text);
		border-radius: 6px;
		padding: 8px 10px;
		font-size: 13px;
	}
	.hint {
		font-size: 11px;
		color: var(--text-dim);
		margin: 0;
	}
	.entry-list {
		max-height: 200px;
		overflow-y: auto;
		border: 1px solid var(--panel-border);
		border-radius: 6px;
		background: var(--bg);
		padding: 8px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}
	.entry-row {
		display: flex;
		align-items: center;
		gap: 6px;
	}
	.entry-row select {
		flex: 1;
	}
	.entry-row input[type='number'] {
		width: 60px;
	}
	.remove-btn {
		background: var(--panel);
		border: 1px solid var(--panel-border);
		color: var(--text-dim);
		border-radius: 4px;
		cursor: pointer;
		padding: 6px 8px;
	}
</style>
