// Client for the Tree Engine backend's /v1 API.
//
// The backend is a stateless generation service: it compiles datapacks handed
// to it and returns preview geometry. It stores nothing, so there is no CRUD
// here - reading and writing the user's project goes through the Go side
// (see project-client.ts). Everything below is "generate something and tell
// me what blocks came out".
//
// Every route requires the launcher's bearer token (see ApiServer.java).

import type { ApiBlockFlags } from './block-flags'
import type { ApiBlock } from './structure'

export interface ModConnection {
	port: number
	token: string
}

// The backend reports failures as {"error": "...", "detail": "..."}, where
// detail carries the actionable part - the codec's complaint about a specific
// datapack file, usually.
export class BackendError extends Error {
	detail?: string
	status: number
	constructor(message: string, status: number, detail?: string) {
		super(message)
		this.detail = detail
		this.status = status
	}
}

async function request<T>(
	conn: ModConnection,
	method: string,
	path: string,
	body?: unknown,
): Promise<T> {
	const res = await fetch(`http://127.0.0.1:${conn.port}${path}`, {
		method,
		headers: {
			Authorization: `Bearer ${conn.token}`,
			...(body === undefined ? {} : { 'Content-Type': 'application/json' }),
		},
		body: body === undefined ? undefined : JSON.stringify(body),
	})

	if (!res.ok) {
		const parsed = await res.json().catch(() => null)
		throw new BackendError(
			parsed?.error ?? `${method} ${path} failed`,
			res.status,
			parsed?.detail,
		)
	}

	const text = await res.text()
	return (text ? JSON.parse(text) : undefined) as T
}

// --- health ---------------------------------------------------------------

export interface HealthInfo {
	status: string
	minecraftVersion: string
	backendVersion: string
	sessions: number
}

export async function health(conn: ModConnection): Promise<HealthInfo> {
	return request(conn, 'GET', '/v1/health')
}

// --- sessions -------------------------------------------------------------

export interface SessionInfo {
	sessionId: string
	fileCount: number
	// True when the backend already had this exact datapack compiled.
	cached: boolean
}

// Compiles a datapack (path -> contents, as GetProjectDatapack returns it)
// into an in-memory registry set and returns a handle to it.
//
// The id is a fingerprint of the content, so re-sending an unchanged project
// is a cache hit rather than a recompile - it is cheap to call this before
// every preview, and that is the intended usage.
export async function createSession(
	conn: ModConnection,
	files: Record<string, string>,
): Promise<SessionInfo> {
	return request(conn, 'POST', '/v1/session', { files })
}

export async function deleteSession(conn: ModConnection, sessionId: string): Promise<void> {
	await request(conn, 'DELETE', `/v1/session/${encodeURIComponent(sessionId)}`)
}

// --- registry -------------------------------------------------------------

// Configured feature ids the backend knows about. With trees=true this is the
// list of things that actually generate trees, which is what the browser and
// the import dialog want.
export async function listFeatures(
	conn: ModConnection,
	options: { sessionId?: string; treesOnly?: boolean } = {},
): Promise<string[]> {
	const params = new URLSearchParams()
	if (options.sessionId) params.set('sessionId', options.sessionId)
	if (options.treesOnly) params.set('trees', 'true')
	const query = params.toString()
	const body = await request<{ features: string[] }>(
		conn,
		'GET',
		`/v1/registry/features${query ? `?${query}` : ''}`,
	)
	return body.features
}

// The datapack JSON for a feature, encoded from the live registry.
//
// This is the source of truth for both importing a vanilla tree and creating
// a new one: the starting config comes from the game rather than a literal in
// the frontend, so it is always correct for the running Minecraft version.
export async function getFeature(
	conn: ModConnection,
	id: string,
	sessionId?: string,
): Promise<unknown> {
	const query = sessionId ? `?sessionId=${encodeURIComponent(sessionId)}` : ''
	return request(conn, 'GET', `/v1/registry/feature/${id}${query}`)
}

// --- single-tree preview --------------------------------------------------

export interface TreePreviewRequest {
	// Inline feature JSON, or featureId to use one from the registry.
	feature?: unknown
	featureId?: string
	// Optional: previews of vanilla-only features need no datapack.
	sessionId?: string
	biome?: string
	seed?: number
	// Include the fabricated soil the tree was grown on.
	includeGround?: boolean
}

export interface TreePreviewResult {
	blocks: ApiBlock[]
	// Rendering hints per distinct block name - feed to registerBlockFlags.
	blockFlags?: Record<string, ApiBlockFlags>
	blockCount: number
	// False when the feature declined to generate - bad soil, not enough room.
	// That is a real outcome worth showing, not an error.
	placed: boolean
}

export async function previewTree(
	conn: ModConnection,
	req: TreePreviewRequest,
): Promise<TreePreviewResult> {
	return request(conn, 'POST', '/v1/preview/tree', req)
}

// --- natural chunk preview ------------------------------------------------

export interface ChunkPreviewRequest {
	sessionId?: string
	chunkX?: number
	chunkZ?: number
	// Chunks across: 1 = one chunk, 2 = 2x2, 3 = 3x3. The backend caps the
	// total. `radius` is the older form and is still accepted, but it cannot
	// express an even span.
	size?: number
	radius?: number
	seed?: number
	// Only the blocks decoration added, rather than the whole chunk.
	decoratedOnly?: boolean
	// Vertical window. Omit both and the backend fits one to the surface,
	// which is almost always what you want - a full column is overwhelmingly
	// underground stone.
	minY?: number
	maxY?: number
}

export interface ChunkPreviewResult {
	blocks: ApiBlock[]
	blockFlags?: Record<string, ApiBlockFlags>
	blockCount: number
	chunkCount: number
	decoratedCount: number
	// False when no session was supplied, i.e. this is vanilla generation.
	datapackApplied: boolean
	// The vertical window actually used.
	minY: number
	maxY: number
}

// Real terrain from the running world, decorated with the session's features.
export async function previewChunk(
	conn: ModConnection,
	req: ChunkPreviewRequest,
): Promise<ChunkPreviewResult> {
	return request(conn, 'POST', '/v1/preview/chunk', req)
}

// --- benchmark ------------------------------------------------------------

export interface BenchmarkResult {
	iterations: number
	totalMs: number
	avgMs: number
	treesPerSecond: number
	avgBlocks: number
}

export async function runBenchmark(
	conn: ModConnection,
	req: { feature?: unknown; featureId?: string; sessionId?: string; iterations?: number },
): Promise<BenchmarkResult> {
	return request(conn, 'POST', '/v1/benchmark', req)
}
