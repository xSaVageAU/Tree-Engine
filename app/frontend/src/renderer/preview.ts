// Orbit-camera WebGL preview around a deepslate StructureRenderer.
//
// Thin wrapper that owns the GL context, an arc-style camera (left-drag to
// rotate, right-drag to pan, wheel to zoom) and the render loop. Feed it a
// BuiltStructure + AssetResources and it draws the tree; call setStructure()
// to swap in a freshly generated one without recreating the GL context.

import { Structure, StructureRenderer } from 'deepslate'
import { mat4, vec3 } from 'gl-matrix'
import { applyBiomeTint, DEFAULT_BIOME } from './biome-colors'
import { AssetResources } from './resources'
import { buildStructure, type ApiBlock, type BuiltStructure } from './structure'

export interface PreviewOptions {
	showGrid?: boolean
	biome?: string
}

// Timing of the last setBlocks call, for the status readout. Kept module-level
// rather than returned so callers that do not care are unaffected.
export interface RenderTimings {
	buildMs: number
	assetsMs: number
	meshMs: number
}

let lastRenderTimings: RenderTimings | null = null

export function takeRenderTimings(): RenderTimings | null {
	const timings = lastRenderTimings
	lastRenderTimings = null
	return timings
}

// Size of the sub-chunks meshing is split into.
//
// Deliberately far below deepslate's default of 16. Measured on a 48x11x48
// bamboo-like structure (~25k blocks, nothing cullable), meshing every slice:
//
//   16^3    9 slices   10.2s total   1230ms worst frame
//    8^3   72 slices    8.0s total    575ms worst frame
//    4^3  432 slices    7.4s total     29ms worst frame
//
// Smaller slices are not just smoother, they are faster overall - each slice
// merges into a much smaller quad array, and that outweighs the extra passes
// over the block list. 4^3 is the point where a slice fits comfortably inside
// a frame.
const SLICE_SIZE = 4

// How long to spend meshing per frame. Under a 16ms frame with room to spare
// for the browser's own work, so the window stays responsive.
const FRAME_BUDGET_MS = 10

// A deepslate Mesh, as far as this file needs to care: something with GL
// buffers that the renderer knows how to draw, and that can be empty.
type DeepslateMesh = { isEmpty(): boolean }

// One entry of ChunkBuilder's internal chunk grid.
type ChunkMeshes = { mesh: DeepslateMesh; transparentMesh: DeepslateMesh }

function nextFrame(): Promise<void> {
	return new Promise((resolve) => requestAnimationFrame(() => resolve()))
}

/**
 * Points an existing renderer at a new structure without triggering a full
 * rebuild.
 *
 * setStructure() would remesh everything synchronously, which is exactly what
 * this avoids. Both fields are private in deepslate's types but are plain
 * assigned properties at runtime, the same reach-past already used for
 * atlasTexture.
 */
function adoptStructure(renderer: StructureRenderer, structure: Structure): void {
	const internals = renderer as unknown as {
		structure: Structure
		chunkBuilder: { structure: Structure }
	}
	internals.structure = structure
	internals.chunkBuilder.structure = structure
}

export class TreePreview {
	private readonly gl: WebGLRenderingContext
	private renderer: StructureRenderer
	private resources: AssetResources
	private structure: BuiltStructure
	private center: [number, number, number]
	private currentBiome: string

	private xRotation = 0.6
	private yRotation = 0.65
	private viewDist = 24
	private dragButton: number | null = null
	private lastX = 0
	private lastY = 0
	private showGrid: boolean
	private autoRotating = false
	private autoRotateHandle: number | null = null

	private constructor(
		canvas: HTMLCanvasElement,
		gl: WebGLRenderingContext,
		built: BuiltStructure,
		resources: AssetResources,
		options: PreviewOptions,
	) {
		this.gl = gl
		this.resources = resources
		this.structure = built
		this.center = built.center
		this.currentBiome = options.biome ?? DEFAULT_BIOME
		this.showGrid = options.showGrid ?? true
		this.viewDist = Math.max(8, Math.max(...built.size) * 1.4)
		applyBiomeTint(this.currentBiome)
		// Empty structure here for the same reason as setBlocks: meshing is
		// done in slices afterwards so the first render does not freeze the
		// window either.
		this.renderer = new StructureRenderer(gl, new Structure(built.size), resources, {
			chunkSize: SLICE_SIZE,
		})
		this.fixAtlasFiltering()
		adoptStructure(this.renderer, built.structure)
		this.attachControls(canvas)
		this.resize(canvas)
	}

	// Creates a preview for an initial set of generated blocks. baseURL points at
	// the provisioned vanilla assets (from EnsureAssets).
	static async create(
		canvas: HTMLCanvasElement,
		blocks: ApiBlock[],
		baseURL: string,
		options: PreviewOptions = {},
	): Promise<TreePreview> {
		const gl = canvas.getContext('webgl')
		if (!gl) throw new Error('WebGL is not available in this environment')
		const built = buildStructure(blocks)
		const resources = await AssetResources.load(baseURL, built.specs)
		const preview = new TreePreview(canvas, gl, built, resources, options)
		await preview.meshInSlices(built.size, ++preview.meshGeneration)
		preview.requestRender()
		return preview
	}

	// Swaps in a newly generated structure. Reloads resources because a different
	// tree may reference blocks/textures the current atlas doesn't contain.
	async setBlocks(
		blocks: ApiBlock[],
		baseURL: string,
		biome = DEFAULT_BIOME,
		onProgress?: (done: number, total: number) => void,
	): Promise<void> {
		const t0 = performance.now()
		const built = buildStructure(blocks)
		const t1 = performance.now()
		this.resources = await AssetResources.load(baseURL, built.specs)
		const t2 = performance.now()
		this.structure = built
		this.center = built.center
		this.currentBiome = biome
		this.viewDist = Math.max(8, Math.max(...built.size) * 1.4)
		applyBiomeTint(biome)

		// Anything already meshing belongs to a superseded structure.
		const generation = ++this.meshGeneration

		// The renderer is built against an *empty* structure so its
		// constructor has nothing to mesh, then the real structure is swapped
		// in and meshed a slice at a time. Handing the real one to the
		// constructor meshes the whole thing synchronously, which is a
		// multi-second freeze on a dense chunk - measured at over 10s for a
		// bamboo forest.
		this.renderer = new StructureRenderer(this.gl, new Structure(built.size), this.resources, {
			chunkSize: SLICE_SIZE,
		})
		this.fixAtlasFiltering()
		adoptStructure(this.renderer, built.structure)

		await this.meshInSlices(built.size, generation, onProgress)

		lastRenderTimings = {
			buildMs: Math.round(t1 - t0),
			assetsMs: Math.round(t2 - t1),
			meshMs: Math.round(performance.now() - t2),
		}
	}

	/**
	 * Meshes the structure a few sub-chunks per frame.
	 *
	 * The total work is unchanged - this is the same geometry either way - but
	 * it is spread across frames so the window keeps responding and the
	 * preview visibly fills in rather than appearing all at once after a
	 * stall.
	 */
	private async meshInSlices(
		size: [number, number, number],
		generation: number,
		onProgress?: (done: number, total: number) => void,
	): Promise<void> {
		const positions: [number, number, number][] = []
		for (let x = 0; x * SLICE_SIZE < size[0]; x++) {
			for (let y = 0; y * SLICE_SIZE < size[1]; y++) {
				for (let z = 0; z * SLICE_SIZE < size[2]; z++) {
					positions.push([x, y, z])
				}
			}
		}

		// Work to a time budget rather than a fixed slice count: slices vary
		// enormously in cost (solid rock versus open air), so a fixed batch
		// either wastes frames on empty ones or overruns on dense ones.
		let done = 0
		while (done < positions.length) {
			if (generation !== this.meshGeneration) return
			const frameStart = performance.now()
			do {
				this.renderer.updateStructureBuffers([positions[done++]])
			} while (done < positions.length && performance.now() - frameStart < FRAME_BUDGET_MS)

			this.requestRender()
			onProgress?.(done, positions.length)
			await nextFrame()
		}
	}

	// Re-tints and rebuilds the mesh for the current structure using an already-
	// loaded resource set - no network refetch needed, just a fast local rebuild.
	setBiome(biome: string): void {
		this.currentBiome = biome
		applyBiomeTint(biome)
		const generation = ++this.meshGeneration
		this.renderer = new StructureRenderer(
			this.gl, new Structure(this.structure.size), this.resources, { chunkSize: SLICE_SIZE })
		this.fixAtlasFiltering()
		adoptStructure(this.renderer, this.structure.structure)
		void this.meshInSlices(this.structure.size, generation)
	}

	// deepslate's StructureRenderer mipmaps the atlas texture but only ever sets
	// TEXTURE_MAG_FILTER, leaving TEXTURE_MIN_FILTER at its WebGL default
	// (NEAREST_MIPMAP_LINEAR). Because atlas cells are packed edge-to-edge with
	// no padding, generating mipmaps averages texels *across* cell boundaries -
	// baking neighbouring textures (e.g. a log's top face) into a block's lower
	// mip levels. That's invisible head-on but shows up as texture bleed at
	// grazing angles, where the GPU's automatic LOD selection picks a coarser
	// mip. Forcing NEAREST (no mipmapping) means only mip level 0 - the exact
	// atlas pixels - is ever sampled, matching the already-NEAREST mag filter.
	private fixAtlasFiltering(): void {
		// atlasTexture is marked `private` in deepslate's .d.ts (API-surface
		// hiding only - it's a plain assigned field at runtime, not a real JS
		// #private, so this cast just reaches past the type declaration).
		const texture = (this.renderer as unknown as { atlasTexture: WebGLTexture }).atlasTexture
		this.gl.bindTexture(this.gl.TEXTURE_2D, texture)
		this.gl.texParameteri(this.gl.TEXTURE_2D, this.gl.TEXTURE_MIN_FILTER, this.gl.NEAREST)
	}

	setShowGrid(enabled: boolean): void {
		this.showGrid = enabled
		this.requestRender()
	}

	resize(canvas: HTMLCanvasElement): void {
		const dpr = window.devicePixelRatio || 1
		const width = Math.max(1, Math.floor(canvas.clientWidth * dpr))
		const height = Math.max(1, Math.floor(canvas.clientHeight * dpr))
		if (canvas.width !== width || canvas.height !== height) {
			canvas.width = width
			canvas.height = height
		}
		this.renderer.setViewport(0, 0, canvas.width, canvas.height)
		this.requestRender()
	}

	private viewMatrix(): mat4 {
		const view = mat4.create()
		mat4.translate(view, view, [0, 0, -this.viewDist])
		mat4.rotateX(view, view, this.xRotation)
		mat4.rotateY(view, view, this.yRotation)
		mat4.translate(view, view, [-this.center[0], -this.center[1], -this.center[2]])
		return view
	}

	private meshGeneration = 0

	private requestRender(): void {
		requestAnimationFrame(() => this.draw())
	}

	private draw(): void {
		const view = this.viewMatrix()
		this.gl.clearColor(0, 0, 0, 0)
		this.gl.clear(this.gl.COLOR_BUFFER_BIT | this.gl.DEPTH_BUFFER_BIT)
		if (this.showGrid) this.renderer.drawGrid(view)
		this.drawStructureInPasses(view)
	}

	/**
	 * Draws the structure as an opaque pass followed by a see-through pass that
	 * does not write depth.
	 *
	 * deepslate's own drawStructure() draws both in one go with depth writes
	 * always on. That is fine for the opaque half but wrong for the other: a
	 * water quad would write depth, and any block behind it - drawn later
	 * because the chunks happen to be ordered that way - then failed the depth
	 * test and vanished outright rather than being blended through. Which
	 * blocks survived depended on the camera angle, since that is what decides
	 * the order the chunks are visited in.
	 *
	 * Masking depth for the see-through pass makes the result independent of
	 * that order: transparent geometry can never occlude anything. It still is
	 * not sorted back-to-front, so two layers of water blend in whatever order
	 * they are drawn, but nothing disappears.
	 */
	private drawStructureInPasses(view: mat4): void {
		// deepslate marks these protected (subclass-only API surface) rather
		// than truly private; this class composes a renderer instead of
		// extending one, so it reaches past the declaration the same way the
		// rest of this file does.
		const internals = this.renderer as unknown as {
			shaderProgram: WebGLProgram
			setShader(shader: WebGLProgram): void
			setTexture(texture: WebGLTexture, pixelSize?: number): void
			prepareDraw(view: mat4): void
			drawMesh(mesh: DeepslateMesh, options: Record<string, boolean>): void
			atlasTexture: WebGLTexture
			resources: { getPixelSize?(): number }
			// ChunkBuilder keeps one opaque and one see-through mesh per chunk.
			// Its public getMeshes() flattens both into a single list with no
			// way to tell them apart, which is exactly the distinction this
			// needs - hence reading the chunk grid rather than calling it.
			chunkBuilder: { chunks: ChunkMeshes[][][] }
		}

		internals.setShader(internals.shaderProgram)
		internals.setTexture(internals.atlasTexture, internals.resources.getPixelSize?.())
		internals.prepareDraw(view)

		const options = { pos: true, color: true, texture: true, normal: true }
		const chunks: ChunkMeshes[] = []
		for (const x of internals.chunkBuilder.chunks ?? []) {
			for (const y of x ?? []) {
				for (const chunk of y ?? []) {
					if (chunk) chunks.push(chunk)
				}
			}
		}

		for (const chunk of chunks) {
			if (!chunk.mesh.isEmpty()) internals.drawMesh(chunk.mesh, options)
		}

		this.gl.depthMask(false)
		for (const chunk of chunks) {
			if (!chunk.transparentMesh.isEmpty()) internals.drawMesh(chunk.transparentMesh, options)
		}
		this.gl.depthMask(true)
	}

	// World-space right/up vectors for the current orbit orientation, used to
	// pan the look-at target in screen-aligned directions regardless of angle.
	private cameraBasis(): { right: vec3; up: vec3 } {
		const rot = mat4.create()
		mat4.rotateX(rot, rot, this.xRotation)
		mat4.rotateY(rot, rot, this.yRotation)
		mat4.invert(rot, rot)
		const right = vec3.transformMat4(vec3.create(), [1, 0, 0], rot)
		const up = vec3.transformMat4(vec3.create(), [0, 1, 0], rot)
		return { right, up }
	}

	private attachControls(canvas: HTMLCanvasElement): void {
		// The renderer owns right-click (camera pan) instead of the browser's
		// native "save image / inspect" menu.
		canvas.addEventListener('contextmenu', (e) => e.preventDefault())

		canvas.addEventListener('pointerdown', (e) => {
			this.dragButton = e.button
			this.lastX = e.clientX
			this.lastY = e.clientY
			canvas.setPointerCapture(e.pointerId)
		})
		canvas.addEventListener('pointerup', (e) => {
			this.dragButton = null
			canvas.releasePointerCapture(e.pointerId)
		})
		canvas.addEventListener('pointermove', (e) => {
			if (this.dragButton === null) return
			const dx = e.clientX - this.lastX
			const dy = e.clientY - this.lastY
			this.lastX = e.clientX
			this.lastY = e.clientY

			if (this.dragButton === 2) {
				// Right-drag: pan the camera (move), not rotate.
				const { right, up } = this.cameraBasis()
				const panScale = this.viewDist * 0.0015
				this.center[0] += -right[0] * dx * panScale + up[0] * dy * panScale
				this.center[1] += -right[1] * dx * panScale + up[1] * dy * panScale
				this.center[2] += -right[2] * dx * panScale + up[2] * dy * panScale
			} else {
				this.yRotation += dx / 100
				this.xRotation += dy / 100
				this.xRotation = Math.max(-Math.PI / 2, Math.min(Math.PI / 2, this.xRotation))
			}
			this.requestRender()
		})
		canvas.addEventListener('wheel', (e) => {
			e.preventDefault()
			this.viewDist = Math.max(4, Math.min(80, this.viewDist + Math.sign(e.deltaY) * 2))
			this.requestRender()
		})
	}

	// Toggles a continuous slow spin around the tree. Runs its own animation
	// loop (rather than the on-demand requestRender used elsewhere) since it
	// needs to keep redrawing with no user input.
	setAutoRotate(enabled: boolean): void {
		this.autoRotating = enabled
		if (!enabled || this.autoRotateHandle !== null) return
		const loop = (): void => {
			if (!this.autoRotating) {
				this.autoRotateHandle = null
				return
			}
			this.yRotation += 0.006
			this.draw()
			this.autoRotateHandle = requestAnimationFrame(loop)
		}
		this.autoRotateHandle = requestAnimationFrame(loop)
	}
}
