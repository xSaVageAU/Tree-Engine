// Orbit-camera WebGL preview around a deepslate StructureRenderer.
//
// Thin wrapper that owns the GL context, an arc-style camera (left-drag to
// rotate, right-drag to pan, wheel to zoom) and the render loop. Feed it a
// BuiltStructure + AssetResources and it draws the tree; call setBlocks() to
// swap in a freshly generated one without recreating the GL context.
//
// Geometry is built by our own ChunkMesher rather than deepslate's ChunkBuilder
// (see chunk-mesher.ts for why). deepslate's StructureRenderer is still used,
// but only for what it is good at: compiling the shaders, owning the atlas
// texture and drawing the bounding grid.

import { Mesh, Structure, StructureRenderer } from 'deepslate'
import { mat4, vec3 } from 'gl-matrix'
import { applyBiomeTint, DEFAULT_BIOME } from './biome-colors'
import { ChunkMesher, type SliceGroup } from './chunk-mesher'
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
// Each slice now costs only what its own contents cost - the mesher reads the
// slice's sub-volume directly instead of filtering the whole structure - so
// bigger slices are strictly better: the same total geometry in fewer meshes,
// fewer GL buffers and fewer draw calls. 16 matches deepslate's own default and
// keeps a slice comfortably inside one frame.
//
// This used to be 4, tuned around two quadratics in deepslate's ChunkBuilder
// that no longer apply. See the header of chunk-mesher.ts.
const SLICE_SIZE = 16

// How long to spend meshing per frame. Under a 16ms frame with room to spare
// for the browser's own work, so the window stays responsive and the preview
// visibly fills in rather than appearing after a stall.
const FRAME_BUDGET_MS = 10

function nextFrame(): Promise<void> {
	return new Promise((resolve) => requestAnimationFrame(() => resolve()))
}

// Frees every GL buffer a deepslate Mesh may have allocated. Mesh.rebuild
// creates buffers lazily and has no teardown of its own, so replacing a mesh
// without this leaks them for the lifetime of the context.
const MESH_BUFFERS = [
	'posBuffer', 'colorBuffer', 'textureBuffer', 'textureLimitBuffer',
	'normalBuffer', 'blockPosBuffer', 'indexBuffer', 'linePosBuffer', 'lineColorBuffer',
] as const

function disposeMesh(gl: WebGLRenderingContext, mesh: Mesh | undefined): void {
	if (!mesh) return
	for (const field of MESH_BUFFERS) {
		const buffer = (mesh as unknown as Record<string, WebGLBuffer | undefined>)[field]
		if (buffer) gl.deleteBuffer(buffer)
	}
}

// The parts of StructureRenderer this file drives directly. deepslate marks
// them private/protected as API-surface hiding, but they are plain assigned
// fields and prototype methods at runtime, so this cast reaches past the
// declaration rather than around real encapsulation.
interface RendererInternals {
	structure: Structure
	resources: AssetResources
	atlasTexture: WebGLTexture
	gridMesh: Mesh
	shaderProgram: WebGLProgram
	createAtlasTexture(image: ImageData): WebGLTexture
	getGridMesh(): Mesh
	setShader(shader: WebGLProgram): void
	setTexture(texture: WebGLTexture, pixelSize?: number): void
	prepareDraw(view: mat4): void
	drawMesh(mesh: unknown, options: Record<string, boolean>): void
}

export class TreePreview {
	private readonly gl: WebGLRenderingContext
	private readonly renderer: StructureRenderer
	private resources: AssetResources
	private structure: BuiltStructure
	private mesher: ChunkMesher
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
	private meshGeneration = 0

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

		// An *empty* structure of the right size: the renderer needs the size for
		// its grid mesh, and nothing else. Handing it real blocks would make its
		// own ChunkBuilder mesh them synchronously in the constructor.
		//
		// useInvisibleBlockBuffer is off because that buffer is both unused and
		// ruinous here: it walks the entire bounding volume and emits a wireframe
		// cube for every *empty* voxel, which on a 48x44x48 preview is 83k cubes -
		// a million Line objects and their vertices - built on every reload and
		// then never drawn.
		this.renderer = new StructureRenderer(gl, new Structure(built.size), resources, {
			chunkSize: SLICE_SIZE,
			useInvisibleBlockBuffer: false,
		})
		this.fixAtlasFiltering()
		this.mesher = new ChunkMesher(gl, built, resources, SLICE_SIZE)
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
		await preview.meshInSlices(preview.mesher, ++preview.meshGeneration)
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

		this.refreshRenderer(built, this.resources)
		this.mesher.dispose()
		this.mesher = new ChunkMesher(this.gl, built, this.resources, SLICE_SIZE)
		await this.meshInSlices(this.mesher, generation, onProgress)

		lastRenderTimings = {
			buildMs: Math.round(t1 - t0),
			assetsMs: Math.round(t2 - t1),
			meshMs: Math.round(performance.now() - t2),
		}
	}

	// Re-tints and rebuilds the mesh for the current structure using an already-
	// loaded resource set - no network refetch needed, just a fast local rebuild.
	// The tint is baked into the mesher's geometry templates, so it needs a fresh
	// mesher; the atlas and grid are unchanged.
	setBiome(biome: string): void {
		this.currentBiome = biome
		applyBiomeTint(biome)
		const generation = ++this.meshGeneration
		this.mesher.dispose()
		this.mesher = new ChunkMesher(this.gl, this.structure, this.resources, SLICE_SIZE)
		void this.meshInSlices(this.mesher, generation)
	}

	/**
	 * Meshes the structure a few slices per frame.
	 *
	 * The total work is unchanged - this is the same geometry either way - but it
	 * is spread across frames so the window keeps responding and the preview
	 * visibly fills in.
	 */
	private async meshInSlices(
		mesher: ChunkMesher,
		generation: number,
		onProgress?: (done: number, total: number) => void,
	): Promise<void> {
		const total = mesher.sliceCount
		let done = 0
		while (done < total) {
			// A newer structure has superseded this one. The mesher it was
			// building is already this.mesher's predecessor and will be disposed
			// by whoever replaced it.
			if (generation !== this.meshGeneration) return
			// Work to a time budget rather than a fixed slice count: slices vary
			// enormously in cost (solid rock versus open air), so a fixed batch
			// either wastes frames on empty ones or overruns on dense ones.
			const frameStart = performance.now()
			do {
				mesher.meshSlice(done++)
			} while (done < total && performance.now() - frameStart < FRAME_BUDGET_MS)

			this.requestRender()
			onProgress?.(done, total)
			await nextFrame()
		}
	}

	/**
	 * Repoints the existing renderer at a new structure's size and resources.
	 *
	 * Constructing a fresh StructureRenderer per reload - which is what this
	 * replaced - recompiled both shader programs every time and leaked the
	 * previous one's atlas texture, grid buffers and programs, so GPU memory grew
	 * with every regenerate. Only two things actually depend on the new
	 * structure: the atlas texture and the bounding grid.
	 */
	private refreshRenderer(built: BuiltStructure, resources: AssetResources): void {
		const internals = this.renderer as unknown as RendererInternals
		internals.structure = new Structure(built.size)
		internals.resources = resources

		this.gl.deleteTexture(internals.atlasTexture)
		internals.atlasTexture = internals.createAtlasTexture(resources.getTextureAtlas())
		this.fixAtlasFiltering()

		disposeMesh(this.gl, internals.gridMesh)
		internals.gridMesh = internals.getGridMesh()
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
		const texture = (this.renderer as unknown as RendererInternals).atlasTexture
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
		const internals = this.renderer as unknown as RendererInternals
		internals.setShader(internals.shaderProgram)
		internals.setTexture(internals.atlasTexture, this.resources.getPixelSize())
		internals.prepareDraw(view)

		const options = { pos: true, color: true, texture: true, normal: true }
		const groups: SliceGroup[] = this.mesher.groups

		for (const group of groups) {
			for (const mesh of group.opaque) internals.drawMesh(mesh, options)
		}

		this.gl.depthMask(false)
		for (const group of groups) {
			for (const mesh of group.transparent) internals.drawMesh(mesh, options)
		}
		this.gl.depthMask(true)

		this.disableStructureAttribs(internals.shaderProgram)
	}

	/**
	 * Leaves no vertex attribute array pointing at this frame's slice buffers.
	 *
	 * enableVertexAttribArray state is global, not per-program, and outlives the
	 * draw call that set it. When a reload frees the previous structure's buffers
	 * those arrays are left referencing deleted objects, and the next draw - the
	 * grid, which runs first and does not touch these locations - fails with
	 * GL_INVALID_OPERATION. The old code never hit this only because it leaked
	 * the buffers instead of deleting them.
	 */
	private disableStructureAttribs(program: WebGLProgram): void {
		for (const name of ['vertPos', 'vertColor', 'texCoord', 'texLimit', 'normal']) {
			const location = this.gl.getAttribLocation(program, name)
			if (location >= 0) this.gl.disableVertexAttribArray(location)
		}
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
