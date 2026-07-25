<script lang="ts">
	import { onMount } from 'svelte'
	import type { ModConnection } from '../renderer/mod-client'
	import { listVanillaTrees } from '../renderer/mod-client'
	import { ImportDatapackFolder, ImportDatapackZip } from '../../wailsjs/go/main/App'
	import Icon from './Icon.svelte'

	let {
		conn,
		onImport,
		onClose,
	}: {
		conn: ModConnection
		onImport: (id: string) => void
		onClose: () => void
	} = $props()

	let allTrees = $state<string[]>([])
	let query = $state('')
	let manualId = $state('')
	let loadError = $state('')

	let datapackStatus = $state('')
	let datapackError = $state('')
	let installingDatapack = $state(false)

	function loadTrees(): void {
		listVanillaTrees(conn)
			.then((ids) => (allTrees = ids))
			.catch((e) => (loadError = e.message))
	}

	onMount(loadTrees)

	const filtered = $derived(allTrees.filter((id) => id.toLowerCase().includes(query.toLowerCase())))

	async function importDatapackZip(): Promise<void> {
		installingDatapack = true
		datapackStatus = ''
		datapackError = ''
		try {
			const name = await ImportDatapackZip()
			if (name) datapackStatus = `Installed "${name}" - restart the server to load its trees.`
		} catch (e) {
			datapackError = (e as Error).message
		} finally {
			installingDatapack = false
		}
	}

	async function importDatapackFolder(): Promise<void> {
		installingDatapack = true
		datapackStatus = ''
		datapackError = ''
		try {
			const name = await ImportDatapackFolder()
			if (name) datapackStatus = `Installed "${name}" - restart the server to load its trees.`
		} catch (e) {
			datapackError = (e as Error).message
		} finally {
			installingDatapack = false
		}
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
	<div class="modal import-modal">
		<div class="modal-head">
			<div class="modal-title"><Icon name="import" size={15} />Import Tree</div>
			<button class="icon-btn" aria-label="Close" onclick={onClose}><Icon name="close" size={15} /></button>
		</div>

		<div class="modal-body">
			<div class="control-group">
				<span class="field-label">By feature ID</span>
				<div class="manual-row">
					<input type="text" placeholder="minecraft:oak" bind:value={manualId} />
					<button class="btn secondary" disabled={!manualId.trim()} onclick={() => onImport(manualId.trim())}>
						Look up
					</button>
				</div>
			</div>

			<div class="control-group grow-group">
				<div class="list-head">
					<span class="field-label">Available trees</span>
					<span class="count-badge">{filtered.length}</span>
				</div>
				<div class="field-search">
					<Icon name="search" size={14} />
					<input type="search" placeholder="Search trees..." bind:value={query} />
				</div>

				<div class="vanilla-list">
					{#if loadError}
						<div class="empty-state">
							<Icon name="alert" size={28} />
							<div class="empty-title">Couldn't load trees</div>
							<div class="empty-copy">{loadError}</div>
						</div>
					{:else if allTrees.length === 0}
						<div class="empty-state">
							<Icon name="spinner" size={26} class="spin" />
							<div class="empty-copy">Loading tree registry...</div>
						</div>
					{:else if filtered.length === 0}
						<div class="empty-state">
							<Icon name="search" size={26} />
							<div class="empty-copy">No trees match "{query}".</div>
						</div>
					{:else}
						{#each filtered as id (id)}
							<div
								class="vanilla-item"
								onclick={() => onImport(id)}
								onkeydown={(e) => e.key === 'Enter' && onImport(id)}
								role="button"
								tabindex="0"
							>
								<span class="vanilla-name">{id.startsWith('minecraft:') ? id.split(':')[1] : id}</span>
								<span class="vanilla-ns mono">{id.split(':')[0]}</span>
							</div>
						{/each}
					{/if}
				</div>
			</div>

			<hr class="divider" />

			<div class="control-group">
				<span class="field-label">Datapacks</span>
				<p class="field-hint">
					Don't see a tree? Install a datapack (e.g. from Modrinth) to add more - a server restart is needed afterward.
				</p>
				<div class="datapack-buttons">
					<button class="btn secondary btn-sm" disabled={installingDatapack} onclick={importDatapackZip}>
						<Icon name="import" size={13} />Add .zip
					</button>
					<button class="btn secondary btn-sm" disabled={installingDatapack} onclick={importDatapackFolder}>
						<Icon name="folder" size={13} />Add folder
					</button>
				</div>
				{#if datapackStatus}
					<div class="callout ok"><Icon name="check" size={14} /><span>{datapackStatus}</span></div>
				{/if}
				{#if datapackError}
					<div class="callout error"><Icon name="alert" size={14} /><span>{datapackError}</span></div>
				{/if}
			</div>
		</div>

		<div class="modal-foot">
			<button class="btn secondary" onclick={onClose}>Cancel</button>
		</div>
	</div>
</div>

<style>
	.import-modal {
		width: min(520px, 92vw);
		height: min(720px, 84vh);
	}

	.control-group {
		display: flex;
		flex-direction: column;
		gap: 7px;
		min-height: 0;
	}

	.grow-group {
		flex: 1;
		min-height: 0;
	}

	.list-head {
		display: flex;
		align-items: center;
		justify-content: space-between;
	}

	.manual-row {
		display: flex;
		gap: 8px;
	}

	.manual-row input {
		flex: 1;
	}

	.vanilla-list {
		flex: 1;
		min-height: 140px;
		overflow-y: auto;
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		background: var(--bg-sunken);
		padding: 4px;
	}

	.vanilla-item {
		display: flex;
		align-items: baseline;
		justify-content: space-between;
		gap: 10px;
		padding: 7px 10px;
		border-radius: var(--r-xs);
		font-size: 12.5px;
		cursor: pointer;
		border: 1px solid transparent;
		transition: background var(--fast), border-color var(--fast);
	}

	.vanilla-item:hover,
	.vanilla-item:focus-visible {
		background: var(--raised);
		border-color: var(--line);
		outline: none;
	}

	.vanilla-item:hover .vanilla-ns {
		color: var(--accent);
	}

	.vanilla-ns {
		font-size: 10.5px;
		color: var(--text-faint);
		flex-shrink: 0;
		transition: color var(--fast);
	}

	.datapack-buttons {
		display: flex;
		gap: 8px;
	}

	.datapack-buttons :global(.btn) {
		flex: 1;
	}
</style>
