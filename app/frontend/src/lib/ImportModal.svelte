<script lang="ts">
	import { onMount } from 'svelte'
	import type { ModConnection } from '../renderer/mod-client'
	import { listVanillaTrees } from '../renderer/mod-client'
	import { ImportDatapackFolder, ImportDatapackZip } from '../../wailsjs/go/main/App'

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
	<div class="modal">
		<h3>Import Tree</h3>

		<div class="manual-row">
			<input type="text" placeholder="Enter ID (e.g. minecraft:oak)" bind:value={manualId} />
			<button class="btn btn-sm" disabled={!manualId.trim()} onclick={() => onImport(manualId.trim())}>Lookup</button>
		</div>

		<input type="search" placeholder="Search trees..." bind:value={query} />

		<div class="vanilla-list">
			{#if loadError}
				<div class="empty error">{loadError}</div>
			{:else if allTrees.length === 0}
				<div class="empty">Loading...</div>
			{:else if filtered.length === 0}
				<div class="empty">No trees found</div>
			{:else}
				{#each filtered as id (id)}
					<div class="vanilla-item" onclick={() => onImport(id)} onkeydown={(e) => e.key === 'Enter' && onImport(id)} role="button" tabindex="0">
						{id.startsWith('minecraft:') ? id.split(':')[1] : id}
					</div>
				{/each}
			{/if}
		</div>

		<div class="datapack-section">
			<div class="datapack-label">
				Don't see a tree? Install a datapack (e.g. from Modrinth) to add more - a server restart is needed afterward.
			</div>
			<div class="datapack-buttons">
				<button class="btn secondary btn-sm" disabled={installingDatapack} onclick={importDatapackZip}>
					Add Datapack (.zip)
				</button>
				<button class="btn secondary btn-sm" disabled={installingDatapack} onclick={importDatapackFolder}>
					Add Datapack (Folder)
				</button>
			</div>
			{#if datapackStatus}
				<div class="datapack-status">{datapackStatus}</div>
			{/if}
			{#if datapackError}
				<div class="datapack-status error">{datapackError}</div>
			{/if}
		</div>

		<button class="btn secondary" onclick={onClose}>Cancel</button>
	</div>
</div>

<style>
	.modal-backdrop {
		position: fixed;
		inset: 0;
		background: rgba(0, 0, 0, 0.5);
		display: flex;
		align-items: center;
		justify-content: center;
		z-index: 2000;
	}
	.modal {
		width: min(480px, 90vw);
		max-height: 80vh;
		background: var(--bg);
		border: 1px solid var(--panel-border);
		border-radius: 8px;
		padding: 20px;
		display: flex;
		flex-direction: column;
		gap: 10px;
		box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
	}
	h3 {
		margin: 0;
	}
	input {
		background: var(--panel);
		border: 1px solid var(--panel-border);
		color: var(--text);
		border-radius: 6px;
		padding: 8px 10px;
		font-size: 13px;
	}
	.manual-row {
		display: flex;
		gap: 8px;
	}
	.manual-row input {
		flex: 1;
	}
	.vanilla-list {
		height: 280px;
		overflow-y: auto;
		border: 1px solid var(--panel-border);
		border-radius: 6px;
	}
	.vanilla-item {
		padding: 8px 12px;
		font-size: 13px;
		cursor: pointer;
		border-bottom: 1px solid var(--panel-border);
	}
	.vanilla-item:hover {
		background: var(--panel);
	}
	.empty {
		padding: 20px;
		text-align: center;
		color: var(--text-dim);
		font-size: 13px;
	}
	.empty.error {
		color: var(--error);
	}
	.datapack-section {
		border-top: 1px solid var(--panel-border);
		padding-top: 10px;
		display: flex;
		flex-direction: column;
		gap: 8px;
	}
	.datapack-label {
		font-size: 12px;
		color: var(--text-dim);
	}
	.datapack-buttons {
		display: flex;
		gap: 8px;
	}
	.datapack-status {
		font-size: 12px;
		color: var(--accent);
	}
	.datapack-status.error {
		color: var(--error);
	}
</style>
