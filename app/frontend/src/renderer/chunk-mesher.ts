// Turns a voxel grid into GL buffers, one slice at a time.
//
// This replaces deepslate's ChunkBuilder, which is unusable for anything
// chunk-sized because of how it handles a partial update. Asking it to remesh
// one sub-chunk makes it walk *every* block in the structure and discard the
// ones that fall outside - and the walk itself is `structure.getBlocks()`,
// which allocates a fresh object per block on every call. Meshing a 48x44x48
// preview in 4^3 slices is 1584 such passes over 18k blocks: 28 million block
// visits and 28 million throwaway objects to place 18 thousand blocks. That was
// ~12 seconds of the preview's load time. It also merges quads with
// `array.concat` per block, which is quadratic in the blocks per slice, so the
// two costs pull the slice size in opposite directions and every choice is bad.
//
// Three things fix it:
//
//   1. Slices read their own sub-volume straight out of the voxel grid, so the
//      total work across all slices is one pass over the volume.
//   2. Geometry is baked once per (blockstate, cull mask) and reused. That pair
//      fully determines the quads a block contributes - deepslate's getMesh has
//      no position or randomness in it - so a chunk with 40 distinct
//      blockstates bakes a few hundred templates instead of running the model
//      pipeline 18 thousand times.
//   3. Vertices are written straight into exact-sized Float32Arrays. No
//      intermediate Mesh, no concat, no flatMap.

import { Mesh, SpecialRenderers, type BlockDefinition, type BlockState } from 'deepslate'
import { bakeWithTintMask, tintKindOf, type BiomeTintMap, type TintKind } from './biome-tint'
import { lookupBlockFlags } from './block-flags'
import type { AssetResources } from './resources'
import type { BuiltStructure } from './structure'

// Vertex indices are Uint16, so one mesh can address 65536 vertices = 16384
// quads. Slices that produce more are split across several meshes; a slice is
// several thousand quads in the worst realistic case, so this rarely triggers,
// but a 16^3 slice of a dense non-cube model could reach it.
const MAX_QUADS_PER_MESH = 16384

// Face order, and the bit each face occupies in a cull mask.
const UP = 0
const NEIGHBOR_DX = [0, 0, -1, 1, 0, 0]
const NEIGHBOR_DY = [1, -1, 0, 0, 0, 0]
const NEIGHBOR_DZ = [0, 0, 0, 0, -1, 1]
const FACE_COUNT = 6
const CULL_MASK_COUNT = 1 << FACE_COUNT

interface Cull {
	up: boolean
	down: boolean
	west: boolean
	east: boolean
	north: boolean
	south: boolean
}

function cullFromMask(mask: number): Cull {
	return {
		up: (mask & 1) !== 0,
		down: (mask & 2) !== 0,
		west: (mask & 4) !== 0,
		east: (mask & 8) !== 0,
		north: (mask & 16) !== 0,
		south: (mask & 32) !== 0,
	}
}

// Everything meshing needs to know about a palette slot, resolved once.
interface PaletteInfo {
	state: BlockState
	// Interned block name. Self-culling compares names only (ignoring
	// properties), so an integer comparison stands in for Identifier.equals.
	nameId: number
	definition: BlockDefinition | null
	opaque: boolean
	selfCulling: boolean
	semiTransparent: boolean
	waterlogged: boolean
	// Which biome colour this block takes, or null if it is not biome-tinted.
	// Only consulted when the mesher was given a tint map.
	tintKind: TintKind | null
}

// One block's geometry at the origin, ready to be copied out with an offset.
// Position is the only attribute that depends on where the block sits, so
// colour is a straight memcpy too - unless biome tinting is on, in which case
// it is a multiply (see buildMeshes).
interface Template {
	quads: number
	pos: Float32Array // 12 per quad (4 vertices x xyz)
	color: Float32Array // 12 per quad
	texture: Float32Array // 8 per quad
	textureLimit: Float32Array // 16 per quad
	normal: Float32Array // 12 per quad
}

function bakeTemplate(mesh: Mesh): Template | null {
	const quads = mesh.quads.length
	if (quads === 0) return null

	const template: Template = {
		quads,
		pos: new Float32Array(quads * 12),
		color: new Float32Array(quads * 12),
		texture: new Float32Array(quads * 8),
		textureLimit: new Float32Array(quads * 16),
		normal: new Float32Array(quads * 12),
	}

	let p = 0, c = 0, t = 0, l = 0, n = 0
	for (const quad of mesh.quads) {
		// Per-quad flat normal, matching what ChunkBuilder.finishChunkMesh
		// computes. Translation does not affect it (it comes from differences
		// between vertices), so baking it before the offset is applied is
		// exactly equivalent to computing it after.
		const normal = quad.normal()
		for (const vertex of [quad.v1, quad.v2, quad.v3, quad.v4]) {
			const { texture, textureLimit } = vertex
			// Mirrors the check in deepslate's own Mesh.rebuild: a face that never
			// had a texture assigned would otherwise be silently packed as zeros
			// and sample the atlas corner.
			if (!texture || !textureLimit) throw new Error('Missing vertex component')

			template.pos[p++] = vertex.pos.x
			template.pos[p++] = vertex.pos.y
			template.pos[p++] = vertex.pos.z
			template.color[c++] = vertex.color[0]
			template.color[c++] = vertex.color[1]
			template.color[c++] = vertex.color[2]
			template.texture[t++] = texture[0]
			template.texture[t++] = texture[1]
			template.textureLimit[l++] = textureLimit[0]
			template.textureLimit[l++] = textureLimit[1]
			template.textureLimit[l++] = textureLimit[2]
			template.textureLimit[l++] = textureLimit[3]
			template.normal[n++] = normal.x
			template.normal[n++] = normal.y
			template.normal[n++] = normal.z
		}
	}
	return template
}

/**
 * A drawable mesh, shaped to satisfy deepslate's Renderer.drawMesh.
 *
 * drawMesh only reads the buffer fields and the three vertex-count methods, so
 * this is a structural stand-in for deepslate's Mesh that skips the quad/vertex
 * object graph entirely. Lines are never used here, so lineVertices() is 0 and
 * the line buffers stay undefined.
 */
export class SliceMesh {
	readonly posBuffer: WebGLBuffer
	readonly colorBuffer: WebGLBuffer
	readonly textureBuffer: WebGLBuffer
	readonly textureLimitBuffer: WebGLBuffer
	readonly normalBuffer: WebGLBuffer
	readonly indexBuffer: WebGLBuffer

	constructor(
		private readonly gl: WebGLRenderingContext,
		private readonly quads: number,
		indexBuffer: WebGLBuffer,
		arrays: Omit<Template, 'quads'>,
	) {
		this.indexBuffer = indexBuffer
		this.posBuffer = uploadArray(gl, arrays.pos)
		this.colorBuffer = uploadArray(gl, arrays.color)
		this.textureBuffer = uploadArray(gl, arrays.texture)
		this.textureLimitBuffer = uploadArray(gl, arrays.textureLimit)
		this.normalBuffer = uploadArray(gl, arrays.normal)
	}

	quadVertices(): number {
		return this.quads * 4
	}

	quadIndices(): number {
		return this.quads * 6
	}

	lineVertices(): number {
		return 0
	}

	isEmpty(): boolean {
		return this.quads === 0
	}

	dispose(): void {
		// The index buffer is shared across every mesh and owned by the mesher.
		this.gl.deleteBuffer(this.posBuffer)
		this.gl.deleteBuffer(this.colorBuffer)
		this.gl.deleteBuffer(this.textureBuffer)
		this.gl.deleteBuffer(this.textureLimitBuffer)
		this.gl.deleteBuffer(this.normalBuffer)
	}
}

function uploadArray(gl: WebGLRenderingContext, data: Float32Array): WebGLBuffer {
	const buffer = gl.createBuffer()
	if (!buffer) throw new Error('Cannot create WebGL buffer')
	gl.bindBuffer(gl.ARRAY_BUFFER, buffer)
	gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW)
	return buffer
}

// Opaque and see-through geometry are drawn in separate passes, so a slice
// keeps them apart (see TreePreview.drawStructureInPasses).
export interface SliceGroup {
	opaque: SliceMesh[]
	transparent: SliceMesh[]
}

// Scratch entry layout: template key, x, y, z, quad count.
const ENTRY_STRIDE = 5

export class ChunkMesher {
	readonly groups: SliceGroup[]
	readonly sliceCount: number

	private readonly paletteInfo: PaletteInfo[]
	private readonly templates = new Map<number, Template | null>()
	private readonly sliceCounts: [number, number, number]
	private readonly sharedIndexBuffer: WebGLBuffer

	// Per-slice scratch, allocated once. A slice can contribute at most one
	// entry per voxel it covers.
	private readonly opaqueEntries: Int32Array
	private readonly transparentEntries: Int32Array
	private opaqueLength = 0
	private transparentLength = 0
	// Reused per block, so tinting a slice allocates nothing.
	private readonly tintScratch = new Float32Array(3)

	constructor(
		private readonly gl: WebGLRenderingContext,
		private readonly built: BuiltStructure,
		private readonly resources: AssetResources,
		private readonly sliceSize: number,
		// Null for the single-tree preview, which tints flatly from one biome.
		private readonly tints: BiomeTintMap | null = null,
	) {
		const nameIds = new Map<string, number>()
		this.paletteInfo = built.palette.map((state) => {
			const name = state.getName()
			const nameKey = name.toString()
			let nameId = nameIds.get(nameKey)
			if (nameId === undefined) {
				nameId = nameIds.size
				nameIds.set(nameKey, nameId)
			}
			const flags = lookupBlockFlags(name)
			return {
				state,
				nameId,
				definition: resources.getBlockDefinition(name),
				opaque: flags?.opaque ?? false,
				selfCulling: flags?.self_culling ?? false,
				semiTransparent: flags?.semi_transparent ?? false,
				waterlogged: state.isWaterlogged(),
				tintKind: tintKindOf(nameKey),
			}
		})

		this.sliceCounts = [
			Math.ceil(built.size[0] / sliceSize),
			Math.ceil(built.size[1] / sliceSize),
			Math.ceil(built.size[2] / sliceSize),
		]
		this.sliceCount = this.sliceCounts[0] * this.sliceCounts[1] * this.sliceCounts[2]
		this.groups = Array.from({ length: this.sliceCount }, () => ({ opaque: [], transparent: [] }))

		const maxEntries = sliceSize * sliceSize * sliceSize * ENTRY_STRIDE
		this.opaqueEntries = new Int32Array(maxEntries)
		this.transparentEntries = new Int32Array(maxEntries)

		// Every mesh draws the same quad-to-triangle index pattern, differing
		// only in how much of it is used, so one buffer serves all of them.
		const indices = new Uint16Array(MAX_QUADS_PER_MESH * 6)
		for (let q = 0; q < MAX_QUADS_PER_MESH; q++) {
			const v = q * 4
			const i = q * 6
			indices[i] = v
			indices[i + 1] = v + 1
			indices[i + 2] = v + 2
			indices[i + 3] = v
			indices[i + 4] = v + 2
			indices[i + 5] = v + 3
		}
		const buffer = gl.createBuffer()
		if (!buffer) throw new Error('Cannot create WebGL index buffer')
		gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, buffer)
		gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, indices, gl.STATIC_DRAW)
		this.sharedIndexBuffer = buffer
	}

	/** Meshes one slice and uploads its buffers. */
	meshSlice(index: number): void {
		const [, ny, nz] = this.sliceCounts
		const sx = Math.floor(index / (ny * nz))
		const sy = Math.floor(index / nz) % ny
		const sz = index % nz
		const s = this.sliceSize
		const [sizeX, sizeY, sizeZ] = this.built.size

		this.collect(
			sx * s, Math.min(sx * s + s, sizeX),
			sy * s, Math.min(sy * s + s, sizeY),
			sz * s, Math.min(sz * s + s, sizeZ),
		)

		const group = this.groups[index]
		for (const mesh of group.opaque) mesh.dispose()
		for (const mesh of group.transparent) mesh.dispose()
		group.opaque = this.buildMeshes(this.opaqueEntries, this.opaqueLength)
		group.transparent = this.buildMeshes(this.transparentEntries, this.transparentLength)
	}

	dispose(): void {
		for (const group of this.groups) {
			for (const mesh of group.opaque) mesh.dispose()
			for (const mesh of group.transparent) mesh.dispose()
			group.opaque = []
			group.transparent = []
		}
		this.gl.deleteBuffer(this.sharedIndexBuffer)
		this.templates.clear()
	}

	// Walks the slice's own sub-volume, resolving each block to a baked
	// template and recording where to stamp it.
	private collect(
		x0: number, x1: number,
		y0: number, y1: number,
		z0: number, z1: number,
	): void {
		this.opaqueLength = 0
		this.transparentLength = 0
		const { voxels, size } = this.built
		const strideX = size[1] * size[2]
		const strideY = size[2]

		for (let x = x0; x < x1; x++) {
			for (let y = y0; y < y1; y++) {
				const rowBase = x * strideX + y * strideY
				for (let z = z0; z < z1; z++) {
					const slot = voxels[rowBase + z]
					if (slot === 0) continue
					const info = this.paletteInfo[slot - 1]
					const mask = this.cullMask(info, x, y, z)

					const key = (slot - 1) * CULL_MASK_COUNT + mask
					let template = this.templates.get(key)
					if (template === undefined) {
						template = this.bake(info, mask)
						this.templates.set(key, template)
					}
					if (template === null) continue

					if (info.semiTransparent) {
						const o = this.transparentLength++ * ENTRY_STRIDE
						this.transparentEntries[o] = key
						this.transparentEntries[o + 1] = x
						this.transparentEntries[o + 2] = y
						this.transparentEntries[o + 3] = z
						this.transparentEntries[o + 4] = template.quads
					} else {
						const o = this.opaqueLength++ * ENTRY_STRIDE
						this.opaqueEntries[o] = key
						this.opaqueEntries[o + 1] = x
						this.opaqueEntries[o + 2] = y
						this.opaqueEntries[o + 3] = z
						this.opaqueEntries[o + 4] = template.quads
					}
				}
			}
		}
	}

	// Integer-only restatement of ChunkBuilder.needsCull for all six faces at
	// once: whether a neighbour hides this block's face depends only on the two
	// blocks' names and flags, all of which are precomputed per palette slot.
	private cullMask(info: PaletteInfo, x: number, y: number, z: number): number {
		const { voxels, size } = this.built
		const [sizeX, sizeY, sizeZ] = size
		const strideX = sizeY * sizeZ

		let mask = 0
		for (let face = 0; face < FACE_COUNT; face++) {
			const nx = x + NEIGHBOR_DX[face]
			const ny = y + NEIGHBOR_DY[face]
			const nz = z + NEIGHBOR_DZ[face]
			// Outside the structure counts as "no neighbour", so the face shows -
			// same as deepslate, whose getBlock returns null out of bounds.
			if (nx < 0 || ny < 0 || nz < 0 || nx >= sizeX || ny >= sizeY || nz >= sizeZ) continue
			const slot = voxels[nx * strideX + ny * sizeZ + nz]
			if (slot === 0) continue
			const other = this.paletteInfo[slot - 1]

			let culled: boolean
			if (info.nameId === other.nameId && other.selfCulling) {
				culled = true
			} else if (other.opaque) {
				culled = !(face === UP && info.waterlogged)
			} else {
				culled = info.waterlogged && other.waterlogged
			}
			if (culled) mask |= 1 << face
		}
		return mask
	}

	private bake(info: PaletteInfo, mask: number): Template | null {
		// With a tint map the template is baked as a mask rather than with a
		// colour in it, and the colour is applied per block when it is stamped.
		if (this.tints) {
			return bakeWithTintMask(() => this.bakeMesh(info, mask))
		}
		return this.bakeMesh(info, mask)
	}

	private bakeMesh(info: PaletteInfo, mask: number): Template | null {
		const cull = cullFromMask(mask)
		const mesh = new Mesh()
		try {
			if (info.definition) {
				mesh.merge(info.definition.getMesh(
					info.state.getName(),
					info.state.getProperties(),
					this.resources,
					this.resources,
					cull,
				))
			}
			// Water, lava, chests, signs and friends live here rather than in the
			// blockstate models. The backend never sends block entity NBT, so
			// this is a pure function of the state and the cull mask too.
			const special = SpecialRenderers.getBlockMesh(info.state, undefined, this.resources, cull)
			if (!special.isEmpty()) mesh.merge(special)
			return bakeTemplate(mesh)
		} catch (e) {
			// Cached as null, so a block with a missing model is reported once
			// rather than once per occurrence.
			console.error(`[renderer] failed to mesh ${info.state.getName().toString()}`, e)
			return null
		}
	}

	// Packs the collected entries into as few meshes as the Uint16 index limit
	// allows, stamping each block's template at its offset.
	private buildMeshes(entries: Int32Array, count: number): SliceMesh[] {
		const meshes: SliceMesh[] = []
		let entry = 0
		while (entry < count) {
			const start = entry
			let quads = 0
			while (entry < count) {
				const next = entries[entry * ENTRY_STRIDE + 4]
				if (quads > 0 && quads + next > MAX_QUADS_PER_MESH) break
				quads += next
				entry++
			}

			const pos = new Float32Array(quads * 12)
			const color = new Float32Array(quads * 12)
			const texture = new Float32Array(quads * 8)
			const textureLimit = new Float32Array(quads * 16)
			const normal = new Float32Array(quads * 12)

			let p = 0, c = 0, t = 0, l = 0, n = 0
			for (let e = start; e < entry; e++) {
				const o = e * ENTRY_STRIDE
				const template = this.templates.get(entries[o])!
				const x = entries[o + 1], y = entries[o + 2], z = entries[o + 3]

				const tp = template.pos
				for (let i = 0; i < tp.length; i += 3) {
					pos[p++] = tp[i] + x
					pos[p++] = tp[i + 1] + y
					pos[p++] = tp[i + 2] + z
				}
				// Biome tinting turns this copy into a blend. The baked colour is
				// a mask - 0 where the model declared a tintindex, 1 where it did
				// not - so `tint + (1 - tint) * mask` yields the biome colour on
				// tinted faces and leaves everything else at 1.
				const info = this.tints
					? this.paletteInfo[Math.floor(entries[o] / CULL_MASK_COUNT)]
					: null
				if (info?.tintKind) {
					const tint = this.tintScratch
					const [ox, , oz] = this.built.origin
					this.tints!.sample(info.tintKind, x + ox, z + oz, tint)
					const tc = template.color
					for (let i = 0; i < tc.length; i += 3) {
						color[c++] = tint[0] + (1 - tint[0]) * tc[i]
						color[c++] = tint[1] + (1 - tint[1]) * tc[i + 1]
						color[c++] = tint[2] + (1 - tint[2]) * tc[i + 2]
					}
				} else {
					color.set(template.color, c); c += template.color.length
				}
				texture.set(template.texture, t); t += template.texture.length
				textureLimit.set(template.textureLimit, l); l += template.textureLimit.length
				normal.set(template.normal, n); n += template.normal.length
			}

			meshes.push(new SliceMesh(this.gl, quads, this.sharedIndexBuffer, {
				pos, color, texture, textureLimit, normal,
			}))
		}
		return meshes
	}
}
