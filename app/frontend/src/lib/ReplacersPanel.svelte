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
	import Icon from './Icon.svelte'

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

	// Running total shown live in the form so the 1.0 cap isn't a surprise on save.
	const chanceTotal = $derived(formAlternatives.reduce((sum, a) => sum + (Number(a.chance) || 0), 0))
</script>

<div class="replacers-panel">
	<div class="panel-head">
		<div class="head-titles">
			<h2>Tree Replacers</h2>
			<span class="head-sub">Swap vanilla world-gen trees for your own</span>
		</div>
		{#if view === 'list'}
			<button class="btn btn-sm" onclick={showCreateForm}><Icon name="plus" size={13} />New Replacer</button>
		{/if}
		<button class="icon-btn" title="Close tab" aria-label="Close tab" onclick={onBack}>
			<Icon name="close" size={15} />
		</button>
	</div>

	{#if statusMessage}
		<div class="status-banner"><Icon name="check" size={14} />{statusMessage}</div>
	{/if}

	<div class="scroll-content">
		<div class="content-width">
			{#if view === 'list'}
				{#if replacers.length === 0}
					<div class="empty-state tall">
						<Icon name="shuffle" size={38} />
						<div class="empty-title">No replacers yet</div>
						<div class="empty-copy">
							A replacer intercepts a vanilla tree during world generation and substitutes one of your custom trees
							instead.
						</div>
						<button class="btn btn-sm" onclick={showCreateForm}><Icon name="plus" size={13} />Create Replacer</button>
					</div>
				{:else}
					<div class="replacer-grid">
						{#each replacers as r (r.id)}
							<div class="replacer-item">
								<div class="replacer-top">
									<div class="replacer-id">
										<h4>{displayName(r.vanilla_tree_id)}</h4>
										<span class="mono dim">{r.vanilla_tree_id}</span>
									</div>
									<span class="type-chip" class:simple={r.type === 'SIMPLE'}>
										{r.type === 'SIMPLE' ? 'Simple' : 'Weighted'}
									</span>
								</div>

								<div class="replacer-body">
									{#if r.type === 'SIMPLE'}
										<div class="kv">
											<span class="k">Pool</span>
											<span class="v">{(r.features ?? []).map((f) => f.split(':').pop()).join(', ') || 'empty'}</span>
										</div>
									{:else}
										<div class="kv">
											<span class="k">Default</span>
											<span class="v">{r.default_tree?.split(':').pop() ?? 'not set'}</span>
										</div>
										<div class="kv">
											<span class="k">Alternatives</span>
											<span class="v">
												{#if (r.alternatives ?? []).length === 0}
													none
												{:else}
													{#each r.alternatives ?? [] as a}
														<span class="alt-chip">
															{a.feature.split(':').pop()}
															<b>{(a.chance * 100).toFixed(0)}%</b>
														</span>
													{/each}
												{/if}
											</span>
										</div>
									{/if}
								</div>

								<div class="replacer-actions">
									<button class="btn secondary btn-sm" onclick={() => showEditForm(r)}>Edit</button>
									<button class="icon-btn danger" aria-label="Delete replacer" onclick={() => remove(r.id)}>
										<Icon name="trash" size={14} />
									</button>
								</div>
							</div>
						{/each}
					</div>
				{/if}
			{:else}
				<div class="form-card">
					<div class="eyebrow">{editingId ? 'Edit' : 'Create'} replacer</div>

					<div class="control-group">
						<label class="field-label" for="replacer-type">Replacer Type</label>
						<select id="replacer-type" bind:value={formType}>
							<option value="WEIGHTED">Weighted (default + chances)</option>
							<option value="SIMPLE">Simple (equal-chance pool)</option>
						</select>
					</div>

					<div class="control-group">
						<label class="field-label" for="replacer-vanilla">Vanilla Tree to Replace</label>
						<select id="replacer-vanilla" bind:value={formVanillaTreeId} disabled={!!editingId}>
							<option value="">Select a vanilla tree...</option>
							{#each vanillaTrees as id (id)}
								<option value={id}>{displayName(id)} ({id})</option>
							{/each}
						</select>
						{#if editingId}
							<p class="field-hint">The vanilla tree can't be changed on an existing replacer.</p>
						{/if}
					</div>

					<hr class="divider" />

					{#if formType === 'WEIGHTED'}
						<div class="control-group">
							<label class="field-label" for="default-tree">Default Tree</label>
							<p class="field-hint">The fallback, used whenever no alternative wins its roll.</p>
							<select id="default-tree" bind:value={formDefaultTree}>
								<option value="">Select default tree...</option>
								{#each customTrees as tree (tree)}
									<option value={`tree_engine:${tree}`}>{tree}</option>
								{/each}
							</select>
						</div>

						<div class="control-group">
							<div class="group-head">
								<span class="field-label" id="alternatives-label">Weighted Alternatives</span>
								{#if formAlternatives.length > 0}
									<span class="count-badge" class:over={chanceTotal > 1}>{(chanceTotal * 100).toFixed(0)}% of 100%</span>
								{/if}
							</div>
							<p class="field-hint">Each alternative has its own probability of replacing the default (0.0-1.0).</p>
							<div class="entry-list" aria-labelledby="alternatives-label">
								{#if customTrees.length === 0}
									<p class="field-hint pad">No custom trees available. Create some trees first.</p>
								{:else if formAlternatives.length === 0}
									<p class="field-hint pad">No alternatives - the default tree is always used.</p>
								{:else}
									{#each formAlternatives as alt, i}
										<div class="entry-row">
											<select bind:value={alt.feature}>
												<option value="">Select tree...</option>
												{#each customTrees as tree (tree)}
													<option value={`tree_engine:${tree}`}>{tree}</option>
												{/each}
											</select>
											<input class="chance" type="number" min="0" max="1" step="0.01" bind:value={alt.chance} />
											<button class="icon-btn danger sm" aria-label="Remove alternative" onclick={() => removeAlternative(i)}>
												<Icon name="close" size={13} />
											</button>
										</div>
									{/each}
								{/if}
							</div>
							<button class="btn secondary full" onclick={addAlternative}>
								<Icon name="plus" size={13} />Add Alternative
							</button>
						</div>
					{:else}
						<div class="control-group">
							<span class="field-label" id="pool-label">Tree Pool</span>
							<p class="field-hint">Every tree in the pool has an equal chance of being picked.</p>
							<div class="entry-list" aria-labelledby="pool-label">
								{#if formPool.length === 0}
									<p class="field-hint pad">The pool is empty. Add at least one tree.</p>
								{:else}
									{#each formPool as _, i}
										<div class="entry-row">
											<select bind:value={formPool[i]}>
												<option value="">Select tree...</option>
												{#each customTrees as tree (tree)}
													<option value={`tree_engine:${tree}`}>{tree}</option>
												{/each}
											</select>
											<button class="icon-btn danger sm" aria-label="Remove tree" onclick={() => removePoolEntry(i)}>
												<Icon name="close" size={13} />
											</button>
										</div>
									{/each}
								{/if}
							</div>
							<button class="btn secondary full" onclick={addPoolEntry}>
								<Icon name="plus" size={13} />Add Tree to Pool
							</button>
						</div>
					{/if}

					<div class="form-actions">
						<button class="btn secondary" onclick={() => (view = 'list')}>Cancel</button>
						<button class="btn" onclick={save}><Icon name="save" size={14} />Save Replacer</button>
					</div>
				</div>
			{/if}
		</div>
	</div>
</div>

<style>
	.replacers-panel {
		flex: 1;
		width: 100%;
		display: flex;
		flex-direction: column;
		background: var(--bg);
		min-width: 0;
	}

	.panel-head {
		display: flex;
		align-items: center;
		gap: 10px;
		padding: 0 14px;
		height: 46px;
		flex-shrink: 0;
		border-bottom: 1px solid var(--line);
		background: var(--panel);
	}

	.head-titles {
		display: flex;
		flex-direction: column;
		gap: 1px;
		flex: 1;
		min-width: 0;
	}

	.head-titles h2 {
		font-size: 13.5px;
		font-weight: 650;
	}

	.head-sub {
		font-size: 11px;
		color: var(--text-faint);
	}

	.status-banner {
		display: flex;
		align-items: center;
		gap: 8px;
		background: var(--accent-wash);
		border-bottom: 1px solid var(--accent-line);
		color: var(--accent-hi);
		padding: 9px 16px;
		font-size: 12px;
		flex-shrink: 0;
	}

	.scroll-content {
		flex: 1;
		overflow-y: auto;
		padding: 20px 16px 32px;
	}

	.content-width {
		max-width: 760px;
		margin: 0 auto;
	}

	.empty-state.tall {
		padding: 64px 24px;
	}

	/* --- List --- */

	.replacer-grid {
		display: grid;
		grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
		gap: 10px;
	}

	.replacer-item {
		background: var(--panel);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 13px 14px;
		display: flex;
		flex-direction: column;
		gap: 10px;
		transition: border-color var(--fast);
	}

	.replacer-item:hover {
		border-color: var(--line-strong);
	}

	.replacer-top {
		display: flex;
		align-items: flex-start;
		justify-content: space-between;
		gap: 10px;
	}

	.replacer-id h4 {
		font-size: 13px;
		font-weight: 650;
	}

	.dim {
		color: var(--text-faint);
		font-size: 10.5px;
	}

	.type-chip {
		font-family: var(--font-mono);
		font-size: 10px;
		font-weight: 600;
		letter-spacing: 0.08em;
		text-transform: uppercase;
		padding: 3px 8px;
		border-radius: var(--r-full);
		background: var(--accent-wash);
		border: 1px solid var(--accent-line);
		color: var(--accent-hi);
		white-space: nowrap;
		flex-shrink: 0;
	}

	.type-chip.simple {
		background: rgba(152, 165, 151, 0.08);
		border-color: var(--line-strong);
		color: var(--text-dim);
	}

	.replacer-body {
		display: flex;
		flex-direction: column;
		gap: 6px;
		border-top: 1px solid var(--line);
		padding-top: 10px;
	}

	.kv {
		display: flex;
		gap: 10px;
		font-size: 11.5px;
		align-items: baseline;
	}

	.kv .k {
		width: 84px;
		flex-shrink: 0;
		color: var(--text-faint);
		font-size: 10.5px;
		font-weight: 700;
		letter-spacing: 0.1em;
		text-transform: uppercase;
	}

	.kv .v {
		color: var(--text-dim);
		display: flex;
		flex-wrap: wrap;
		gap: 4px;
		min-width: 0;
		word-break: break-word;
	}

	.alt-chip {
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-xs);
		padding: 1px 6px;
		font-size: 11px;
	}

	.alt-chip b {
		color: var(--accent);
		font-weight: 600;
		margin-left: 3px;
	}

	.replacer-actions {
		display: flex;
		gap: 6px;
		justify-content: flex-end;
	}

	/* --- Form --- */

	.form-card {
		background: var(--panel);
		border: 1px solid var(--line);
		border-radius: var(--r-lg);
		padding: 20px;
		display: flex;
		flex-direction: column;
		gap: 16px;
		box-shadow: var(--shadow-md);
	}

	.control-group {
		display: flex;
		flex-direction: column;
		gap: 7px;
	}

	.group-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
		gap: 10px;
	}

	.count-badge.over {
		color: var(--danger);
		border-color: rgba(226, 102, 74, 0.4);
		background: var(--danger-wash);
	}

	.entry-list {
		max-height: 240px;
		overflow-y: auto;
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		background: var(--bg-sunken);
		padding: 8px;
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	.field-hint.pad {
		padding: 12px 4px;
		text-align: center;
	}

	.entry-row {
		display: flex;
		align-items: center;
		gap: 6px;
	}

	.entry-row select {
		flex: 1;
		min-width: 0;
	}

	.entry-row .chance {
		width: 74px;
		flex-shrink: 0;
		font-family: var(--font-mono);
		text-align: center;
	}

	.entry-row :global(.icon-btn.sm) {
		width: 28px;
		height: 28px;
		flex-shrink: 0;
	}

	.form-actions {
		display: flex;
		gap: 8px;
		justify-content: flex-end;
		border-top: 1px solid var(--line);
		padding-top: 16px;
	}
</style>
