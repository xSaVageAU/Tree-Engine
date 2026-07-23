// Orbit-camera WebGL preview around a deepslate StructureRenderer.
//
// Thin wrapper that owns the GL context, an arc-style camera (left-drag to
// rotate, right-drag to pan, wheel to zoom) and the render loop. Feed it a
// BuiltStructure + AssetResources and it draws the tree; call setStructure()
// to swap in a freshly generated one without recreating the GL context.

import { StructureRenderer } from 'deepslate'
import { mat4, vec3 } from 'gl-matrix'
import { applyBiomeTint, DEFAULT_BIOME } from './biome-colors'
import { AssetResources } from './resources'
import { buildStructure, type ApiBlock, type BuiltStructure } from './structure'

export interface PreviewOptions {
	showGrid?: boolean
	biome?: string
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
		this.renderer = new StructureRenderer(gl, built.structure, resources)
		this.fixAtlasFiltering()
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
		preview.requestRender()
		return preview
	}

	// Swaps in a newly generated structure. Reloads resources because a different
	// tree may reference blocks/textures the current atlas doesn't contain.
	async setBlocks(blocks: ApiBlock[], baseURL: string, biome = DEFAULT_BIOME): Promise<void> {
		const built = buildStructure(blocks)
		this.resources = await AssetResources.load(baseURL, built.specs)
		this.structure = built
		this.center = built.center
		this.currentBiome = biome
		this.viewDist = Math.max(8, Math.max(...built.size) * 1.4)
		applyBiomeTint(biome)
		this.renderer = new StructureRenderer(this.gl, built.structure, this.resources)
		this.fixAtlasFiltering()
		this.requestRender()
	}

	// Re-tints and rebuilds the mesh for the current structure using an already-
	// loaded resource set - no network refetch needed, just a fast local rebuild.
	setBiome(biome: string): void {
		this.currentBiome = biome
		applyBiomeTint(biome)
		this.renderer = new StructureRenderer(this.gl, this.structure.structure, this.resources)
		this.fixAtlasFiltering()
		this.requestRender()
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

	private requestRender(): void {
		requestAnimationFrame(() => this.draw())
	}

	private draw(): void {
		const view = this.viewMatrix()
		this.gl.clearColor(0, 0, 0, 0)
		this.gl.clear(this.gl.COLOR_BUFFER_BIT | this.gl.DEPTH_BUFFER_BIT)
		if (this.showGrid) this.renderer.drawGrid(view)
		this.renderer.drawStructure(view)
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
