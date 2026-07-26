// Builds a deepslate `Resources` object from provisioned vanilla client assets.
//
// This is the heart of the data-driven renderer: instead of hardcoding how each
// block looks, we feed deepslate the game's own blockstate definitions, block
// models (with parent inheritance) and textures - served by the launcher at
// baseURL (see Go internal/mcassets + the /mcassets asset handler). deepslate
// then draws every block exactly as Minecraft does.
//
// deepslate's resource getters are synchronous, so everything a structure needs
// is fetched and assembled up front in load(), driven by the exact set of
// blockstates present (so we only ever fetch the handful of models/textures a
// given tree actually uses, not the entire vanilla asset set).

import {
	BlockDefinition,
	BlockModel,
	Identifier,
	TextureAtlas,
	type BlockFlags,
	type BlockModelProvider,
	type Resources,
	type UV,
} from 'deepslate'
import { lookupBlockFlags } from './block-flags'
import type { BlockSpec } from './structure'

// The launcher's dev asset server can transiently drop a request under a burst
// of concurrent fetches, which would silently leave a block un-textured. Retry
// a few times before giving up so provisioning is deterministic.
async function fetchWithRetry(url: string, attempts = 4): Promise<Response | null> {
	for (let i = 0; i < attempts; i++) {
		try {
			const res = await fetch(url)
			if (res.ok) return res
		} catch {
			// network hiccup - fall through to retry
		}
		if (i < attempts - 1) await new Promise((r) => setTimeout(r, 50 * (i + 1)))
	}
	return null
}

// A dev server's SPA fallback (Vite serving index.html for any unmatched
// path) returns 200 OK with an HTML body instead of a real 404 - fetchOk
// alone doesn't catch that. Guard on Content-Type so a routing miss surfaces
// as a normal "missing" result instead of a JSON.parse crash.
async function fetchJsonUncached(url: string): Promise<any | null> {
	const res = await fetchWithRetry(url)
	if (!res) return null
	const contentType = res.headers.get('content-type') ?? ''
	if (!contentType.includes('json')) {
		console.warn(`[renderer] expected JSON from ${url}, got content-type "${contentType}" - treating as missing`)
		return null
	}
	return res.json()
}

// Fetched assets are cached by URL for the lifetime of the window.
//
// These are immutable files for a fixed Minecraft version, served from a local
// cache, and the same handful is needed on every render - so refetching them
// each time is pure waste. It is the *promise* that is cached, not the result,
// which also collapses concurrent requests for the same URL into one.
//
// This matters far more than it sounds. A single tree needs a few blockstates;
// a chunk needs dozens, fanning out to hundreds of models and textures. Without
// this, every regenerate paid for all of them again.
// Runs fn over items with at most `limit` in flight.
//
// Not Promise.all: these loops used to be strictly sequential because a burst
// of concurrent requests could make the asset server drop some, leaving a
// block silently un-textured. A bounded pool keeps that protection while
// still overlapping the latency, which is the whole cost here.
async function mapLimit<T>(items: T[], limit: number, fn: (item: T) => Promise<void>): Promise<void> {
	let next = 0
	const workers = Array.from({ length: Math.min(limit, items.length) }, async () => {
		while (next < items.length) {
			await fn(items[next++])
		}
	})
	await Promise.all(workers)
}

const MAX_IN_FLIGHT = 8

const jsonCache = new Map<string, Promise<any | null>>()
const textureCache = new Map<string, Promise<Blob | null>>()

function fetchJson(url: string): Promise<any | null> {
	let pending = jsonCache.get(url)
	if (!pending) {
		pending = fetchJsonUncached(url)
		jsonCache.set(url, pending)
	}
	return pending
}

function fetchTexture(url: string): Promise<Blob | null> {
	let pending = textureCache.get(url)
	if (!pending) {
		pending = (async () => {
			const res = await fetchWithRetry(url)
			const contentType = res?.headers.get('content-type') ?? ''
			if (res && contentType.includes('image')) return res.blob()
			if (res) console.warn(`[renderer] expected image from ${url}, got content-type "${contentType}"`)
			return null
		})()
		textureCache.set(url, pending)
	}
	return pending
}

// deepslate 0.26.0's TextureAtlas.fromBlobs mis-sizes the atlas for certain
// texture counts: its upperPowerOfTwo() is integer bit-twiddling code, but
// fromBlobs calls it with a Math.sqrt(...) float. JS's bitwise operators
// truncate to int32 first, so e.g. upperPowerOfTwo(2.236) truncates to 2,
// sees 2 is already a power of two, and wrongly returns 2 instead of 4 - any
// time a structure needs 4-7 distinct textures (so sqrt(count+1) lands just
// above an existing power of two), the atlas ends up one size too small and
// the last texture(s) land outside the [0,1] UV space, wrapping around onto
// the reserved "invalid texture" tile. Build the atlas ourselves instead,
// using the same public TextureAtlas constructor but correct sizing.
async function buildAtlas(blobs: Record<string, Blob>): Promise<TextureAtlas> {
	const ids = Object.keys(blobs)
	let width = 1
	while (width * width < ids.length + 1) width *= 2
	const part = 1 / width
	const pixelWidth = width * 16

	const canvas = document.createElement('canvas')
	canvas.width = pixelWidth
	canvas.height = pixelWidth
	const ctx = canvas.getContext('2d')!
	// Slot 0 is reserved for the invalid-texture placeholder, matching
	// deepslate's own convention (TextureAtlas.drawInvalidTexture).
	ctx.fillStyle = 'black'
	ctx.fillRect(0, 0, 16, 16)
	ctx.fillStyle = 'magenta'
	ctx.fillRect(0, 0, 8, 8)
	ctx.fillRect(8, 8, 8, 8)

	// Decode every texture up front instead of one per loop iteration:
	// createImageBitmap is asynchronous and off-thread, so awaiting them one at a
	// time serialised work the browser is happy to overlap.
	const bitmaps = await Promise.all(ids.map((id) => createImageBitmap(blobs[id])))

	const idMap: Record<string, UV> = {}
	let index = 1
	for (const [i, id] of ids.entries()) {
		const u = index % width
		const v = Math.floor(index / width)
		index += 1
		idMap[id] = [part * u, part * v, part * u + part, part * v + part]
		const img = bitmaps[i]
		// Sample the WHOLE source texture (whatever its native resolution -
		// vanilla is 16x16, but resource packs are commonly 32x32 up to 128x128
		// or higher) and scale it down into this atlas cell. The atlas itself
		// stays fixed at 16x16 per cell because deepslate's own UV math (see
		// BlockModel.getElementMesh: `du = (u1-u0)/16`) assumes that scale.
		// Hardcoding the source rect to (0,0,16,16) - the original bug here -
		// only sampled a texture's top-left 16x16 pixel corner for any
		// higher-resolution pack, cropping instead of downscaling it.
		ctx.drawImage(img, 0, 0, img.width, img.height, 16 * u, 16 * v, 16, 16)
	}

	return new TextureAtlas(ctx.getImageData(0, 0, pixelWidth, pixelWidth), idMap)
}

// Diagnostics from the most recent AssetResources.load(), for surfacing what
// resolved vs. failed without needing devtools. Temporary spike aid.
export const rendererDiagnostics: {
	missingModels: string[]
	failedTextures: string[]
	modelsLoaded: string[]
	texturesLoaded: string[]
	textureIdsWanted: string[]
	resolvedTextureRefs: Record<string, string>
	atlasIdMapKeys: string[]
} = {
	missingModels: [],
	failedTextures: [],
	modelsLoaded: [],
	texturesLoaded: [],
	textureIdsWanted: [],
	resolvedTextureRefs: {},
	atlasIdMapKeys: [],
}

export class AssetResources implements Resources {
	private constructor(
		private readonly definitions: Map<string, BlockDefinition>,
		private readonly models: Map<string, BlockModel>,
		private readonly atlas: TextureAtlas,
	) {}

	getBlockDefinition(id: Identifier): BlockDefinition | null {
		return this.definitions.get(id.toString()) ?? null
	}

	getBlockModel(id: Identifier): BlockModel | null {
		return this.models.get(id.toString()) ?? null
	}

	// Hot path: called for every face of every baked block template. It used to
	// format and push a trace string per call, which on a chunk-sized preview was
	// six figures of string allocation into an array that was never bounded.
	getTextureUV(id: Identifier): UV {
		return this.atlas.getTextureUV(id)
	}

	getTextureAtlas(): ImageData {
		return this.atlas.getTextureAtlas()
	}

	getPixelSize(): number {
		return this.atlas.getPixelSize()
	}

	// Answered from what the backend reported for this block name - see
	// block-flags.ts for why returning null here was not merely a missed
	// optimization but the cause of water hiding the terrain beneath it.
	getBlockFlags(id: Identifier): BlockFlags | null {
		return lookupBlockFlags(id)
	}

	// The mod serializes complete blockstate properties, so deepslate never has
	// to fall back to defaults; returning null here is intentional.
	getBlockProperties(_id: Identifier): Record<string, string[]> | null {
		return null
	}

	getDefaultBlockProperties(_id: Identifier): Record<string, string> | null {
		return null
	}

	// Assembles resources for exactly the blockstates in `specs`.
	static async load(baseURL: string, specs: BlockSpec[]): Promise<AssetResources> {
		const base = baseURL.endsWith('/') ? baseURL : baseURL + '/'

		const definitions = new Map<string, BlockDefinition>()
		const models = new Map<string, BlockModel>()
		const modelJson = new Map<string, any>() // includes parents; null = missing
		const textureIds = new Set<string>()

		// Ensure a model and its whole parent chain are fetched, collecting the
		// concrete (non-variable) texture ids each declares along the way.
		const ensureModel = async (modelId: Identifier): Promise<void> => {
			const key = modelId.toString()
			if (modelJson.has(key)) return
			const data = await fetchJson(`${base}models/${modelId.path}.json`)
			modelJson.set(key, data)
			if (!data) return
			if (data.textures) {
				for (const value of Object.values(data.textures)) {
					if (typeof value === 'string' && !value.startsWith('#')) {
						textureIds.add(Identifier.parse(value).toString())
					}
				}
			}
			if (typeof data.parent === 'string') {
				await ensureModel(Identifier.parse(data.parent))
			}
		}

		// Load each distinct block's definition, then resolve the model variants
		// its actual properties select.
		const distinctNames = [...new Set(specs.map((spec) => spec.name))]
		await mapLimit(distinctNames, MAX_IN_FLIGHT, async (name) => {
			const nameId = Identifier.parse(name)
			const data = await fetchJson(`${base}blockstates/${nameId.path}.json`)
			if (data) definitions.set(nameId.toString(), BlockDefinition.fromJson(data))
		})

		// Model variants can only be resolved once the definitions exist, so
		// this is a second pass rather than part of the one above.
		const seenModels = new Set<string>()
		const wantedModels: string[] = []
		for (const spec of specs) {
			const def = definitions.get(Identifier.parse(spec.name).toString())
			if (!def) continue
			for (const variant of def.getModelVariants(spec.properties)) {
				if (seenModels.has(variant.model)) continue
				seenModels.add(variant.model)
				wantedModels.push(variant.model)
			}
		}
		await mapLimit(wantedModels, MAX_IN_FLIGHT, (model) => ensureModel(Identifier.parse(model)))

		// Build model objects, then flatten each against the full set so parent
		// elements/textures are inlined.
		for (const [key, data] of modelJson) {
			if (data) models.set(key, BlockModel.fromJson(data))
		}
		const provider: BlockModelProvider = {
			getBlockModel: (id: Identifier) => models.get(id.toString()) ?? null,
		}
		for (const model of models.values()) {
			model.flatten(provider)
		}

		// Fetch every referenced texture and pack them into an atlas.
		const blobs: Record<string, Blob> = {}
		const failedTextures: string[] = []
		await mapLimit([...textureIds], MAX_IN_FLIGHT, async (idStr) => {
			const id = Identifier.parse(idStr)
			const blob = await fetchTexture(`${base}textures/${id.path}.png`)
			if (blob) {
				blobs[idStr] = blob
			} else {
				failedTextures.push(idStr)
			}
		})

		// Diagnostics: surface exactly what resolved vs. what didn't, including the
		// concrete texture identifier each model's faces actually resolve to at
		// render time (via its private getTexture - TS `private` is erased at
		// runtime, so this is a legitimate introspection hook, not a hack around
		// real encapsulation).
		const missingModels = [...modelJson.entries()].filter(([, d]) => !d).map(([k]) => k)
		const resolvedTextureRefs: Record<string, string> = {}
		for (const [key, model] of models) {
			const anyModel = model as any
			const firstFace = anyModel.elements?.[0]?.faces
			const ref = firstFace ? Object.values(firstFace)[0] as any : undefined
			if (ref?.texture) {
				try {
					resolvedTextureRefs[key] = anyModel.getTexture(ref.texture).toString()
				} catch (e) {
					resolvedTextureRefs[key] = `ERROR: ${(e as Error).message}`
				}
			}
		}
		// Only failures are logged. The full resolved lists live in
		// rendererDiagnostics for the diagnostics panel to read on demand -
		// dumping them to the console on every load made devtools retain and
		// render hundreds of entries per regenerate for nobody's benefit.
		if (missingModels.length) console.warn('[renderer] models MISSING:', missingModels)
		if (failedTextures.length) console.warn('[renderer] textures FAILED:', failedTextures)
		rendererDiagnostics.missingModels = missingModels
		rendererDiagnostics.failedTextures = failedTextures
		rendererDiagnostics.modelsLoaded = [...models.keys()]
		rendererDiagnostics.texturesLoaded = Object.keys(blobs)
		rendererDiagnostics.textureIdsWanted = [...textureIds]
		rendererDiagnostics.resolvedTextureRefs = resolvedTextureRefs

		const atlas = Object.keys(blobs).length > 0
			? await buildAtlas(blobs)
			: TextureAtlas.empty()

		// Ground truth: deepslate's own idMap, bypassing any assumption that it
		// matches our `blobs` keys (TS `private` is erased at runtime, so this is
		// legitimate introspection, not a hack around real encapsulation).
		rendererDiagnostics.atlasIdMapKeys = Object.keys((atlas as any).idMap ?? {})

		return new AssetResources(definitions, models, atlas)
	}
}
