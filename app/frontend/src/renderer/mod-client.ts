// Thin client for the mod's HTTP API (see mod WebEditorServer/TreeApiHandler).
// Every /api/* route requires the launcher's Bearer token (see AuthFilter) and,
// from a webview origin, needs the mod's CORS support to actually complete.

import type { ApiBlock } from './structure'

export interface ModConnection {
	port: number
	token: string
}

function authHeaders(token: string): HeadersInit {
	return { Authorization: `Bearer ${token}` }
}

async function getJson(conn: ModConnection, path: string): Promise<any> {
	const res = await fetch(`http://127.0.0.1:${conn.port}${path}`, {
		headers: authHeaders(conn.token),
	})
	if (!res.ok) throw new Error(`GET ${path} -> HTTP ${res.status}`)
	return res.json()
}

async function postJson(conn: ModConnection, path: string, body: any): Promise<any> {
	const res = await fetch(`http://127.0.0.1:${conn.port}${path}`, {
		method: 'POST',
		headers: { ...authHeaders(conn.token), 'Content-Type': 'application/json' },
		body: JSON.stringify(body),
	})
	if (!res.ok) {
		const text = await res.text().catch(() => '')
		throw new Error(`POST ${path} -> HTTP ${res.status} ${text}`)
	}
	const responseText = await res.text()
	return responseText ? JSON.parse(responseText) : undefined
}

async function del(conn: ModConnection, path: string): Promise<void> {
	const res = await fetch(`http://127.0.0.1:${conn.port}${path}`, {
		method: 'DELETE',
		headers: authHeaders(conn.token),
	})
	if (!res.ok) throw new Error(`DELETE ${path} -> HTTP ${res.status}`)
}

// Lists vanilla ConfiguredFeature ids the mod's registry knows about, e.g.
// "minecraft:oak", "minecraft:birch".
export async function listVanillaTrees(conn: ModConnection): Promise<string[]> {
	return getJson(conn, '/api/vanilla_trees')
}

// Fetches the raw ConfiguredFeature JSON for a vanilla tree - the exact shape
// /api/generate expects as its request body.
export async function getVanillaTree(conn: ModConnection, id: string): Promise<any> {
	return getJson(conn, `/api/vanilla_tree/${encodeURIComponent(id)}`)
}

// Thrown by generateTree on a generation failure; `details` carries the mod's
// extra diagnostic text (e.g. the DataResult parse error) when present.
export class GenerateError extends Error {
	details?: string
	constructor(message: string, details?: string) {
		super(message)
		this.details = details
	}
}

// Runs the mod's real PhantomWorld generation for a ConfiguredFeature and
// returns the placed blocks - the same data the renderer adapter consumes.
export async function generateTree(conn: ModConnection, feature: any): Promise<ApiBlock[]> {
	const res = await fetch(`http://127.0.0.1:${conn.port}/api/generate`, {
		method: 'POST',
		headers: { ...authHeaders(conn.token), 'Content-Type': 'application/json' },
		body: JSON.stringify(feature),
	})
	if (!res.ok) {
		// The mod reports failures as {"error": "...", "details": "..."} JSON.
		const body = await res.json().catch(() => null)
		if (body?.error) throw new GenerateError(body.error, body.details)
		throw new GenerateError(`HTTP ${res.status}`)
	}
	return res.json()
}

// Convenience: generate a named vanilla tree in one call, preferring
// preferredId if the registry has it, else falling back to the first
// available vanilla tree.
export async function generateVanillaTree(
	conn: ModConnection,
	preferredId = 'minecraft:oak',
): Promise<{ id: string; blocks: ApiBlock[] }> {
	const ids = await listVanillaTrees(conn)
	if (ids.length === 0) throw new Error('mod reports no vanilla trees in its registry')
	const id = ids.includes(preferredId) ? preferredId : ids[0]
	const feature = await getVanillaTree(conn, id)
	const blocks = await generateTree(conn, feature)
	return { id, blocks }
}

// --- Custom tree CRUD (config/tree_engine/datapacks/tree_engine_trees) ---

export async function listTrees(conn: ModConnection): Promise<string[]> {
	return getJson(conn, '/api/trees')
}

export async function getTree(conn: ModConnection, id: string): Promise<any> {
	return getJson(conn, `/api/trees/${encodeURIComponent(id)}`)
}

// Saves a tree's ConfiguredFeature JSON under id, creating or overwriting it.
export async function saveTree(conn: ModConnection, id: string, feature: any): Promise<{ id: string }> {
	return postJson(conn, `/api/trees/${encodeURIComponent(id)}`, feature)
}

export async function deleteTree(conn: ModConnection, id: string): Promise<void> {
	return del(conn, `/api/trees/${encodeURIComponent(id)}`)
}

// A tree's placement (PlacedFeature) rules are stored separately from its
// ConfiguredFeature - fetching one that doesn't exist yet 404s; callers should
// fall back to an empty default (see mod TreeManager.selectTree behavior).
export async function getPlacement(conn: ModConnection, id: string): Promise<any> {
	return getJson(conn, `/api/trees/${encodeURIComponent(id)}/placement`)
}

export async function savePlacement(conn: ModConnection, id: string, placement: any): Promise<void> {
	await postJson(conn, `/api/trees/${encodeURIComponent(id)}/placement`, placement)
}

// --- Tree replacers (vanilla tree -> custom tree pool substitution) ---

export interface ReplacerAlternative {
	feature: string
	chance: number
}

export interface Replacer {
	id: string
	vanilla_tree_id: string
	type: 'WEIGHTED' | 'SIMPLE'
	default_tree?: string
	alternatives?: ReplacerAlternative[]
	features?: string[]
}

export async function listReplacers(conn: ModConnection): Promise<Replacer[]> {
	return getJson(conn, '/api/replacers')
}

export async function getReplacer(conn: ModConnection, id: string): Promise<Replacer> {
	return getJson(conn, `/api/replacers/${encodeURIComponent(id)}`)
}

export async function saveReplacer(conn: ModConnection, replacer: Replacer): Promise<Replacer> {
	return postJson(conn, '/api/replacers', replacer)
}

export async function deleteReplacer(conn: ModConnection, id: string): Promise<void> {
	return del(conn, `/api/replacers/${encodeURIComponent(id)}`)
}

// --- Misc ---

// Reloads all trees and replacers from disk into the running game registry -
// call after saving so changes take effect without a server restart.
export async function hotReload(conn: ModConnection): Promise<void> {
	await postJson(conn, '/api/hot-reload', undefined)
}

export interface BenchmarkResult {
	totalTimeMs: number
	avgTimeMs: number
	treesPerSecond: number
	iterations: number
}

export async function runBenchmark(conn: ModConnection, feature: any, iterations: number): Promise<BenchmarkResult> {
	return postJson(conn, '/api/benchmark', { feature, iterations })
}
