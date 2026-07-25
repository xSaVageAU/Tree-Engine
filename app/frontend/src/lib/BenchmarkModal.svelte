<script lang="ts">
	import { runBenchmark, type BenchmarkResult, type ModConnection } from '../renderer/mod-client'
	import Icon from './Icon.svelte'

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
	<div class="modal bench-modal">
		<div class="modal-head">
			<div class="modal-title"><Icon name="bolt" size={15} />Performance Benchmark</div>
			<button class="icon-btn" aria-label="Close" onclick={onClose}><Icon name="close" size={15} /></button>
		</div>

		<div class="modal-body">
			<p class="field-hint">
				Generates this tree repeatedly on the server and reports how fast world-gen can place it.
			</p>

			<div class="control-group">
				<label class="field-label" for="iterations">Iterations</label>
				<input id="iterations" type="number" min="100" max="10000" step="100" bind:value={iterations} />
			</div>

			{#if error}
				<div class="callout error"><Icon name="alert" size={14} /><span>{error}</span></div>
			{:else if result}
				<div class="results">
					<div class="stat hero">
						<div class="stat-label">Trees / second</div>
						<div class="stat-value">{Math.round(result.treesPerSecond).toLocaleString()}</div>
						<div class="spark"></div>
					</div>
					<div class="stat">
						<div class="stat-label">Avg time</div>
						<div class="stat-value small">{result.avgTimeMs.toFixed(3)}<span class="unit">ms</span></div>
					</div>
					<div class="stat">
						<div class="stat-label">Total ({result.iterations.toLocaleString()} trees)</div>
						<div class="stat-value small">{result.totalTimeMs.toFixed(1)}<span class="unit">ms</span></div>
					</div>
				</div>
			{:else}
				<div class="results placeholder-results">
					<div class="stat hero"><div class="stat-label">Trees / second</div><div class="stat-value dim">--</div></div>
					<div class="stat"><div class="stat-label">Avg time</div><div class="stat-value small dim">--</div></div>
					<div class="stat"><div class="stat-label">Total</div><div class="stat-value small dim">--</div></div>
				</div>
			{/if}
		</div>

		<div class="modal-foot">
			<button class="btn secondary" onclick={onClose}>Close</button>
			<button class="btn" disabled={running} onclick={run}>
				<Icon name={running ? 'spinner' : 'bolt'} size={14} class={running ? 'spin' : ''} />
				{running ? 'Running...' : 'Run Benchmark'}
			</button>
		</div>
	</div>
</div>

<style>
	.bench-modal {
		width: min(430px, 92vw);
	}

	.control-group {
		display: flex;
		flex-direction: column;
		gap: 7px;
	}

	.results {
		display: grid;
		grid-template-columns: 1fr 1fr;
		gap: 8px;
	}

	.stat {
		background: var(--bg-sunken);
		border: 1px solid var(--line);
		border-radius: var(--r-md);
		padding: 11px 13px;
		display: flex;
		flex-direction: column;
		gap: 3px;
		position: relative;
		overflow: hidden;
	}

	.stat.hero {
		grid-column: span 2;
		border-color: var(--accent-line);
		background: linear-gradient(135deg, var(--accent-wash), transparent 70%), var(--bg-sunken);
	}

	.stat-label {
		font-size: 10.5px;
		font-weight: 700;
		letter-spacing: 0.1em;
		text-transform: uppercase;
		color: var(--text-faint);
	}

	.stat-value {
		font-family: var(--font-mono);
		font-size: 27px;
		font-weight: 600;
		line-height: 1.1;
		color: var(--accent-hi);
		letter-spacing: -0.02em;
	}

	.stat-value.small {
		font-size: 17px;
		color: var(--text);
	}

	.stat-value.dim {
		color: var(--line-strong);
	}

	.unit {
		font-size: 11px;
		color: var(--text-faint);
		margin-left: 3px;
		font-weight: 400;
	}

	/* Decorative canopy sheen behind the headline figure. */
	.spark {
		position: absolute;
		right: -20px;
		top: -30px;
		width: 130px;
		height: 130px;
		border-radius: 50%;
		background: radial-gradient(circle, rgba(139, 197, 63, 0.18), transparent 62%);
		pointer-events: none;
	}

	.placeholder-results {
		opacity: 0.55;
	}
</style>
