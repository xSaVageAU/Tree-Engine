// Converts the backend's preview output into a dense voxel grid.
//
// The backend runs the real Minecraft generation logic and returns a flat list
// of placed blockstates (see BlockDto.java): { x, y, z, name, properties? }.
// We translate every block to a zero-based local origin and intern its
// (name, properties) pair into a palette, leaving a grid of small integers.
//
// This deliberately does *not* build a deepslate `Structure`. Meshing needs two
// things from the block data - "what is at (x,y,z)" for neighbour culling, and
// "walk the blocks in this sub-volume" - and a typed array answers both in O(1)
// with no allocation. deepslate's Structure answers the first by allocating a
// fresh PlacedBlock per query and the second only by walking every block in the
// whole structure, which is what made meshing quadratic (see chunk-mesher.ts).

import { BlockState } from 'deepslate'

export interface ApiBlock {
	x: number
	y: number
	z: number
	name: string
	properties?: Record<string, string> | null
}

// A distinct blockstate that appears in the structure - the unit the asset
// loader resolves models/textures for.
export interface BlockSpec {
	name: string
	properties: Record<string, string>
}

export interface BuiltStructure {
	size: [number, number, number]
	// Geometric center in local coordinates, for aiming the camera.
	center: [number, number, number]
	// Palette index + 1 per voxel, so 0 reads as "empty". Indexed
	// x * sizeY * sizeZ + y * sizeZ + z.
	voxels: Int32Array
	palette: BlockState[]
	// One entry per palette slot, in palette order - the asset loader wants
	// distinct blockstates, which is exactly what the palette is.
	specs: BlockSpec[]
}

const EMPTY: BuiltStructure = {
	size: [1, 1, 1],
	center: [0.5, 0.5, 0.5],
	voxels: new Int32Array(1),
	palette: [],
	specs: [],
}

export function buildStructure(blocks: ApiBlock[]): BuiltStructure {
	if (blocks.length === 0) return EMPTY

	let minX = Infinity, minY = Infinity, minZ = Infinity
	let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity
	for (const b of blocks) {
		if (b.x < minX) minX = b.x
		if (b.y < minY) minY = b.y
		if (b.z < minZ) minZ = b.z
		if (b.x > maxX) maxX = b.x
		if (b.y > maxY) maxY = b.y
		if (b.z > maxZ) maxZ = b.z
	}

	const size: [number, number, number] = [
		maxX - minX + 1,
		maxY - minY + 1,
		maxZ - minZ + 1,
	]

	const strideX = size[1] * size[2]
	const voxels = new Int32Array(size[0] * strideX)
	const palette: BlockState[] = []
	const specs: BlockSpec[] = []
	// Interning by string key rather than deepslate's own palette lookup, which
	// is a linear findIndex + BlockState.equals per block.
	const paletteIndex = new Map<string, number>()

	for (const b of blocks) {
		const properties = b.properties ?? {}
		const key = b.name + '|' + JSON.stringify(properties)
		let index = paletteIndex.get(key)
		if (index === undefined) {
			index = palette.length
			paletteIndex.set(key, index)
			palette.push(new BlockState(b.name, properties))
			specs.push({ name: b.name, properties })
		}
		voxels[(b.x - minX) * strideX + (b.y - minY) * size[2] + (b.z - minZ)] = index + 1
	}

	return {
		size,
		center: [size[0] / 2, size[1] / 2, size[2] / 2],
		voxels,
		palette,
		specs,
	}
}
