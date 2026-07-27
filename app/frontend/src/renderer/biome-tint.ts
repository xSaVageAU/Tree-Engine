// Per-column biome tints for the world preview, blurred the way the game
// blurs them.
//
// The single-tree preview picks one biome and colours everything with it (see
// biome-colors.ts). That is right for a tree floating on a fabricated plane and
// wrong for real terrain, where a preview can span a forest, a swamp and a
// desert and the boundaries between them are a thing you want to see.
//
// Colours arrive already computed by the server, straight off the game's own
// Biome. Nothing here knows or decides what colour a biome is - it only decides
// which column gets which, and how neighbouring columns bleed into each other.

import { BlockColors, type Color } from 'deepslate'

import type { ApiBiomeGrid } from './mod-client'

// Vanilla averages the tint over a square of this radius around the block
// (BlockTintCache / Level.getBlockTint), which is what stops biome borders
// from being a hard seam. Matching the radius matters: too small and borders
// look stepped, too large and small biomes wash out.
const BLEND_RADIUS = 2

// Which tint a block family takes. Anything not listed is untinted, which is
// most blocks - stone does not care what biome it is in.
export type TintKind = 'grass' | 'foliage' | 'dryFoliage' | 'water'

// Mirrors the families deepslate's BlockColors tints, minus the ones vanilla
// gives a fixed colour regardless of biome (birch and spruce leaves).
const TINT_KINDS = new Map<string, TintKind>()
for (const id of [
	'grass_block', 'short_grass', 'tall_grass', 'fern', 'large_fern',
	'potted_fern', 'pink_petals', 'wildflowers', 'bush', 'sugar_cane',
]) TINT_KINDS.set(id, 'grass')
for (const id of [
	'oak_leaves', 'jungle_leaves', 'acacia_leaves', 'dark_oak_leaves',
	'mangrove_leaves', 'vine',
]) TINT_KINDS.set(id, 'foliage')
for (const id of ['leaf_litter']) TINT_KINDS.set(id, 'dryFoliage')
for (const id of ['water', 'water_cauldron', 'bubble_column']) TINT_KINDS.set(id, 'water')

/** The tint family a block belongs to, or null if it is not biome-tinted. */
export function tintKindOf(name: string): TintKind | null {
	const id = name.startsWith('minecraft:') ? name.slice('minecraft:'.length) : name
	return TINT_KINDS.get(id) ?? null
}

const BLACK: Color = [0, 0, 0]

/**
 * Bakes geometry with every biome-tinted face forced to black, and everything
 * else left alone.
 *
 * This is how a per-position tint survives a template cache that is keyed on
 * (blockstate, cull mask) and nothing else. deepslate gives a face the tint if
 * its model declares a `tintindex` and exactly [1,1,1] if it does not, so
 * baking with black leaves each vertex marked: 0 means "this face takes the
 * biome colour", 1 means "leave it alone". A grass block's dirt underside is
 * untinted in its model and therefore stays untinted here, which a blanket
 * multiply over the whole block would get wrong.
 *
 * Restores the previous entries afterwards, so the single-tree preview's flat
 * tint (applyBiomeTint) is unaffected by a world preview having been rendered.
 */
export function bakeWithTintMask<T>(build: () => T): T {
	const saved = new Map<string, ((props: Record<string, string>) => Color) | undefined>()
	for (const id of TINT_KINDS.keys()) {
		saved.set(id, BlockColors[id])
		BlockColors[id] = () => BLACK
	}
	try {
		return build()
	} finally {
		for (const [id, previous] of saved) {
			if (previous === undefined) delete BlockColors[id]
			else BlockColors[id] = previous
		}
	}
}

/**
 * Blurred tints for one preview, as linear 0..1 RGB ready to multiply into
 * vertex colours.
 *
 * Stored as four separate planes rather than one array of colours because the
 * mesher asks for a single family at a time, and this way that lookup is three
 * contiguous reads instead of a pointer chase.
 */
export class BiomeTintMap {
	private readonly originX: number
	private readonly originZ: number
	private readonly width: number
	private readonly depth: number
	private readonly planes: Record<TintKind, Float32Array>

	private constructor(grid: ApiBiomeGrid) {
		this.originX = grid.originX
		this.originZ = grid.originZ
		this.width = grid.width
		this.depth = grid.depth
		this.planes = {
			grass: blurPlane(grid, (e) => e.grass),
			foliage: blurPlane(grid, (e) => e.foliage),
			dryFoliage: blurPlane(grid, (e) => e.dryFoliage),
			water: blurPlane(grid, (e) => e.water),
		}
	}

	static from(grid: ApiBiomeGrid): BiomeTintMap | null {
		if (grid.palette.length === 0 || grid.columns.length !== grid.width * grid.depth) {
			return null
		}
		return new BiomeTintMap(grid)
	}

	/**
	 * Writes the tint for a world column into `out`. Columns outside the grid
	 * clamp to the edge rather than going untinted - decoration legitimately
	 * spills past the requested chunks, and a bright white tree hanging off the
	 * border is far more obvious than one tinted with its neighbour's colour.
	 */
	sample(kind: TintKind, x: number, z: number, out: Float32Array): void {
		const cx = clamp(x - this.originX, 0, this.width - 1)
		const cz = clamp(z - this.originZ, 0, this.depth - 1)
		const plane = this.planes[kind]
		const i = (cz * this.width + cx) * 3
		out[0] = plane[i]
		out[1] = plane[i + 1]
		out[2] = plane[i + 2]
	}
}

function clamp(value: number, lo: number, hi: number): number {
	return value < lo ? lo : value > hi ? hi : value
}

// Box-blurs one colour channel set across the grid. Separable: a horizontal
// pass then a vertical one, so the cost is linear in the radius rather than
// quadratic. At 160x160 with radius 2 that is the difference between 128k and
// 640k samples - not fatal either way, but this runs on every regenerate.
function blurPlane(
	grid: ApiBiomeGrid,
	channel: (entry: { grass: number; foliage: number; dryFoliage: number; water: number }) => number,
): Float32Array {
	const { width, depth, columns, palette } = grid

	// Unpack the palette once, so the blur reads floats instead of doing a
	// palette lookup and three shifts per sample.
	const paletteRgb = new Float32Array(palette.length * 3)
	for (let i = 0; i < palette.length; i++) {
		const hex = channel(palette[i])
		paletteRgb[i * 3] = ((hex >> 16) & 0xff) / 255
		paletteRgb[i * 3 + 1] = ((hex >> 8) & 0xff) / 255
		paletteRgb[i * 3 + 2] = (hex & 0xff) / 255
	}

	const source = new Float32Array(width * depth * 3)
	for (let i = 0; i < columns.length; i++) {
		const p = columns[i] * 3
		source[i * 3] = paletteRgb[p]
		source[i * 3 + 1] = paletteRgb[p + 1]
		source[i * 3 + 2] = paletteRgb[p + 2]
	}

	const horizontal = new Float32Array(source.length)
	for (let z = 0; z < depth; z++) {
		for (let x = 0; x < width; x++) {
			let r = 0, g = 0, b = 0, n = 0
			for (let d = -BLEND_RADIUS; d <= BLEND_RADIUS; d++) {
				const sx = clamp(x + d, 0, width - 1)
				const i = (z * width + sx) * 3
				r += source[i]; g += source[i + 1]; b += source[i + 2]; n++
			}
			const o = (z * width + x) * 3
			horizontal[o] = r / n; horizontal[o + 1] = g / n; horizontal[o + 2] = b / n
		}
	}

	const out = new Float32Array(source.length)
	for (let z = 0; z < depth; z++) {
		for (let x = 0; x < width; x++) {
			let r = 0, g = 0, b = 0, n = 0
			for (let d = -BLEND_RADIUS; d <= BLEND_RADIUS; d++) {
				const sz = clamp(z + d, 0, depth - 1)
				const i = (sz * width + x) * 3
				r += horizontal[i]; g += horizontal[i + 1]; b += horizontal[i + 2]; n++
			}
			const o = (z * width + x) * 3
			out[o] = r / n; out[o + 1] = g / n; out[o + 2] = b / n
		}
	}
	return out
}
