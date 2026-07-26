// Approximate per-biome foliage/grass tints, applied by overriding deepslate's
// BlockColors table (see node_modules/deepslate/lib/render/BlockColors.js).
//
// deepslate hardcodes oak/jungle/acacia/dark_oak/mangrove leaves + vine to a
// single default ("plains") foliage color, and the grass family to a single
// default grass color - real Minecraft samples these per-biome from a
// colormap texture keyed by temperature/downfall. These hex values are a
// direct port of the same approximation table the project's original
// Babylon-based renderer used (mod web/js/config.js BIOME_COLORS), not a
// colormap sample, but they're the values this project has already shipped
// and validated visually.
//
// spruce_leaves/birch_leaves are intentionally left alone: vanilla (and
// deepslate) already gives those a fixed color independent of biome.

import { BlockColors, type Color } from 'deepslate'

export const BIOME_COLORS: Record<string, string> = {
	plains: '#77AB2F',
	forest: '#59AE30',
	birch_forest: '#6BA941',
	jungle: '#30BB0B',
	sparse_jungle: '#3EB80F',
	swamp: '#6A7039',
	mangrove: '#8DB127',
	desert: '#AEA42A',
	badlands: '#9E814D',
	snowy: '#60A17B',
	taiga: '#68A464',
	meadow: '#63A948',
	mushroom: '#2BBB0F',
	pale_garden: '#878D76',
	cherry: '#B6DB61',
}

export const DEFAULT_BIOME = 'plains'

// Block ids whose deepslate BlockColors entry depends on the biome-sampled
// foliage color and should be overridden when the biome changes.
const BIOME_FOLIAGE_BLOCKS = ['oak_leaves', 'jungle_leaves', 'acacia_leaves', 'dark_oak_leaves', 'mangrove_leaves', 'vine']

// Block ids whose deepslate BlockColors entry depends on the biome-sampled
// grass color.
const BIOME_GRASS_BLOCKS = [
	'grass_block', 'short_grass', 'tall_grass', 'large_fern', 'fern',
	'potted_fern', 'pink_petals', 'wildflowers', 'bush', 'sugar_cane',
]

function hexToColor(hex: string): Color {
	const n = parseInt(hex.replace('#', ''), 16)
	return [((n >> 16) & 0xff) / 255, ((n >> 8) & 0xff) / 255, (n & 0xff) / 255]
}

// Mutates deepslate's shared BlockColors table in place (it's a plain
// exported object, not frozen) so every subsequent render uses this biome's
// tints. Must be called before building/rendering a structure.
export function applyBiomeTint(biome: string): void {
	const hex = BIOME_COLORS[biome] ?? BIOME_COLORS[DEFAULT_BIOME]
	const color = hexToColor(hex)
	for (const id of BIOME_FOLIAGE_BLOCKS) BlockColors[id] = () => color
	for (const id of BIOME_GRASS_BLOCKS) BlockColors[id] = () => color
}
