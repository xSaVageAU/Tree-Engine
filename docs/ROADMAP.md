# Roadmap

## Where things stand

The backend was rewritten from scratch for Minecraft 26.2 as a stateless
generation service. Both preview modes work and are verified against a running
server. The 2.0 rewrite is merged to `main`.

The full flow — clone, build, first-run setup, server boot, open project, edit,
preview — has been run start to finish. A clean clone reaches a working binary
with a single `wails build`.

| Capability | State |
|---|---|
| Datapack compiled in memory, no disk | working |
| Single-tree preview | working |
| Natural chunk preview, datapack applied | working |
| Registry browsing and feature import | working |
| Tree + placement editing | working |
| Tree replacers, as datapack shadowing | working |
| Benchmark | working |
| Precise per-file datapack errors | working |

## Next

**Chunk preview transfer cost.** The renderer is no longer the bottleneck — a
3×3 meshes in ~35ms, down from over ten seconds, and the whole frontend side of
a preview is under 100ms. What is left is upstream: ~1.3s of generation and a
4.6MB JSON body for a 3×3, which is ~60k objects of
`{x, y, z, name, properties}` to serialise and parse.

A palette plus a packed position array would cut that by roughly an order of
magnitude. Not worth doing at today's numbers, but it is the lever to pull
before raising the 9-chunk cap or lowering the y=50 floor, since both scale it
linearly.

**Direct overwrites.** Replacers already prove the shadowing mechanism: write a
configured feature under another namespace and Minecraft loads yours instead.
The remaining step is editing a vanilla or modded tree *directly* — saving an
edited `minecraft:oak` as itself rather than as a selector pool. Most of the
work is done; what is missing is accepting a namespaced id in the save path
(and skipping placed-feature generation for it, since the original's placement
rules already point at the shadowed id) plus the UI to start such an edit.

**Structures in natural previews.** Currently disabled for boot time, so a
chunk containing a village previews without it. Worth revisiting once the chunk
view exists and the cost can be measured rather than guessed.

## Considered and parked

**Schema-driven form editor.** A form UI as an alternative to raw JSON.
Investigated previously: no library covers this. Misode's `@mcschema/core` is
years stale and its actual Minecraft schema *definitions* were never published
reusably. Doing it well means a purpose-built form for `TreeConfiguration` and
friends. It must not mean hand-writing a JSON schema — those go stale silently
between Minecraft versions, which is the failure this project already avoids by
reading configs from the live registry.

**Resource pack overlay.** Layering a custom pack's textures over vanilla in
the preview. Attempted and reverted: higher-resolution packs (64×, 128×) did
not scale into the atlas correctly, and it created more friction than value.
Vanilla-only rendering is the baseline; worth retrying given real demand.

**Multi-version support.** The backend targets one Minecraft version at a time.
Supporting several simultaneously means a jar per version and a resolver in the
launcher. Worth doing only if there is real demand for versions behind current.

## Out of scope

**In-game editing.** An interactive GUI inside Minecraft. The desktop app is
the editor, and the backend has deliberately been stripped of all in-game
presence to keep it simple.
