<script lang="ts">
	import { runBenchmark, type BenchmarkResult, type ModConnection } from '../renderer/mod-client'

	let { conn, feature, onClose }: { conn: ModConnection; feature: any; onClose: () => void } = $props()

	let iterations = $state(1000)
	let running = $state(false)
	let result = $state<BenchmarkResult | null>(null)
	let error = $state('')

	async function run(): Promise<void> {
		running = true
		result = null
		error = ''
		try {
			result = await runBenchmark(conn, feature, iterations)
		} catch (e) {
			error = (e as Error).message
		} finally {
			running = false
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
		<h3>Tree Performance Benchmark</h3>
		<p>Run a performance test on this tree configuration?</p>

		<div class="control-group">
			<label for="iterations">Iterations</label>
			<input id="iterations" type="number" min="100" max="10000" bind:value={iterations} />
		</div>

		{#if error}
			<div class="error">Error: {error}</div>
		{:else if result}
			<div class="results">
				<div class="stat">
					<div class="stat-label">Trees / Second</div>
					<div class="stat-value accent">{Math.round(result.treesPerSecond).toLocaleString()}</div>
				</div>
				<div class="stat">
					<div class="stat-label">Avg Time</div>
					<div class="stat-value">{result.avgTimeMs.toFixed(3)} ms</div>
				</div>
				<div class="stat wide">
					<div class="stat-label">Total Time ({result.iterations} trees)</div>
					<div class="stat-value small">{result.totalTimeMs.toFixed(1)} ms</div>
				</div>
			</div>
		{/if}

		<div class="row">
			<button class="btn" disabled={running} onclick={run}>{running ? 'Running...' : 'Run Benchmark'}</button>
			<button class="btn secondary" onclick={onClose}>Close</button>
		</div>
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
		width: min(400px, 90vw);
		background: var(--bg);
		border: 1px solid var(--panel-border);
		border-radius: 8px;
		padding: 20px;
		box-shadow: 0 12px 40px rgba(0, 0, 0, 0.4);
	}
	h3 {
		margin: 0 0 8px;
	}
	p {
		margin: 0 0 10px;
		color: var(--text-dim);
		font-size: 13px;
	}
	.control-group {
		display: flex;
		flex-direction: column;
		gap: 6px;
		margin-bottom: 10px;
	}
	.control-group label {
		font-size: 12px;
		color: var(--text-dim);
	}
	input {
		background: var(--panel);
		border: 1px solid var(--panel-border);
		color: var(--text);
		border-radius: 6px;
		padding: 8px 10px;
		font-size: 13px;
	}
	.error {
		color: var(--error);
		font-size: 13px;
		margin-bottom: 10px;
	}
	.results {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 10px;
		margin-bottom: 10px;
	}
	.stat {
		background: var(--panel);
		padding: 10px;
		border-radius: 6px;
	}
	.stat.wide {
		grid-column: span 2;
	}
	.stat-label {
		font-size: 11px;
		color: var(--text-dim);
	}
	.stat-value {
		font-size: 18px;
		font-weight: bold;
	}
	.stat-value.accent {
		color: var(--accent);
	}
	.stat-value.small {
		font-size: 14px;
		font-weight: normal;
	}
	.row {
		display: flex;
		gap: 10px;
		justify-content: flex-end;
	}
</style>
