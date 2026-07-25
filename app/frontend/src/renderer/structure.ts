// Converts the backend's preview output into a deepslate Structure.
//
// The backend runs the real Minecraft generation logic and returns a flat list
// of placed blockstates (see BlockDto.java): { x, y, z, name, properties? }.
// deepslate's Structure is a bounded voxel grid, so we translate every block to
// a zero-based local origin and record the distinct (name, properties) specs
// the resource loader needs.

import { Structure } from 'deepslate'

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
	structure: Structure
	size: [number, number, number]
	// Geometric center in local coordinates, for aiming the camera.
	center: [number, number, number]
	specs: BlockSpec[]
}

export function buildStructure(blocks: ApiBlock[]): BuiltStructure {
	if (blocks.length === 0) {
		return {
			structure: new Structure([1, 1, 1]),
			size: [1, 1, 1],
			center: [0.5, 0.5, 0.5],
			specs: [],
		}
	}

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

	const structure = new Structure(size)
	const specs = new Map<string, BlockSpec>()

	for (const b of blocks) {
		const properties = b.properties ?? {}
		structure.addBlock([b.x - minX, b.y - minY, b.z - minZ], b.name, properties)
		const key = b.name + '|' + JSON.stringify(properties)
		if (!specs.has(key)) {
			specs.set(key, { name: b.name, properties })
		}
	}

	return {
		structure,
		size,
		center: [size[0] / 2, size[1] / 2, size[2] / 2],
		specs: [...specs.values()],
	}
}
