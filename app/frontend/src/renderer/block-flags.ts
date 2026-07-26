// Per-block rendering hints, as reported by the backend (see BlockFlagsDto.java).
//
// deepslate asks its Resources for these while meshing, and answering "no idea"
// - which is what we used to do - makes it treat every block as an opaque,
// depth-writing cube. Two things go wrong with that. Nothing gets face-culled,
// so every buried block is meshed in full; and see-through blocks land in the
// opaque draw bucket, where water drawn before the ground behind it writes
// depth and the ground then fails the depth test. That is why a lake blotted
// out the terrain under it, and why rotating the camera changed which blocks
// survived: it changed the order the chunks happened to be drawn in.
//
// A block name maps to the same flags for the whole lifetime of a Minecraft
// version, so this is a cumulative registry rather than something threaded
// through each render: once a name has been seen, re-rendering a cached
// structure needs no fresh lookup.

import type { Identifier } from 'deepslate'
import type { BlockFlags } from 'deepslate'

// Wire shape from the backend, keyed by block name.
export interface ApiBlockFlags {
	opaque: boolean
	semiTransparent: boolean
	selfCulling: boolean
}

const flags = new Map<string, BlockFlags>()

export function registerBlockFlags(reported: Record<string, ApiBlockFlags> | undefined): void {
	if (!reported) return
	for (const [name, f] of Object.entries(reported)) {
		flags.set(name, {
			opaque: f.opaque,
			semi_transparent: f.semiTransparent,
			self_culling: f.selfCulling,
		})
	}
}

export function lookupBlockFlags(id: Identifier): BlockFlags | null {
	return flags.get(id.toString()) ?? null
}
