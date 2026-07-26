# Architecture

How Tree Engine is put together, and the reasoning behind the parts that are
not obvious. This describes the system as it exists; see
[ROADMAP.md](ROADMAP.md) for what is planned.

## Shape

```
┌─ app/ ────────────────────────────┐        ┌─ backend/ ──────────────────┐
│  Svelte editor + deepslate         │  HTTP  │  Fabric mod on a headless   │
│  Go: project files, server mgmt  ──┼───────▶│  Minecraft server           │
│  Owns all persistence              │  :port │  Owns nothing               │
└────────────────────────────────────┘        └─────────────────────────────┘
         │                                                  │
         ▼                                                  ▼
   your project folder                            in-memory registries
   (a plain datapack)                             (discarded on eviction)
```

One rule explains most of the design: **the backend owns no files.** The app
reads and writes the project; the backend receives datapack content in a
request, compiles it in memory, answers, and forgets. Nothing is shared through
the filesystem, which is what makes each half changeable without the other.

## Why the backend is stateless

The previous version was not, and it is worth recording why that was abandoned.
It stored trees under its own config directory, mutated the running game's
registries through reflection to apply changes live, registered a resource-pack
provider through a mixin, and exposed CRUD over HTTP. Five responsibilities in
one mod, with file ownership split ambiguously between the mod and the app.

Statelessness removes the ambiguity. There is exactly one copy of your project,
in your folder, written by one component. The backend became a pure function
from *(datapack, request)* to *blocks*, which is both simpler and far easier to
reason about when something looks wrong.

## Compiling a datapack in memory

`InMemoryPack` implements Minecraft's `PackResources` over a
`Map<Identifier, byte[]>`. `RegistrySet.compile` hands it to
`RegistryDataLoader` and gets back a frozen `RegistryAccess`.

Two details are load-bearing, both established by running them rather than by
reading:

1. **The pack is appended to the server's existing packs, never used alone.**
   `WORLDGEN_REGISTRIES` loads every worldgen registry in one pass, and several
   of them (`pig_variant`, `wolf_variant`, the sound variants) fail a non-empty
   validator if vanilla's own data is absent. Layering is also the semantics we
   want: the user's datapack overrides vanilla.
2. **Base lookups come from the layer *below* worldgen.** Passing the worldgen
   layer itself duplicates the registries being loaded.

Sessions are keyed by a content fingerprint, so re-sending an unchanged project
is a cache hit. Eviction is never a correctness problem — re-uploading produces
the same id.

## The two preview modes

They answer different questions and share no code beyond `BlockDto`, the block
format they both emit.

### Single tree — `preview/tree/`

One feature, generated at the origin on a fabricated soil plane. No terrain, no
neighbours, no placement rules.

The fiction lives in exactly one place. `GroundPlane` fabricates the soil and
the biome; `CaptureLevel` is a `WorldGenLevel` that records placements instead
of writing a world; `FlatGenerator` is a `ChunkGenerator` that reports the flat
plane and refuses everything else.

### Natural chunks — `preview/chunk/`

Real terrain from the running world, decorated with the session's features.

`TerrainSnapshot` copies a chunk at `SURFACE` status — terrain and surface
materials present, features not yet placed, which is exactly the state to
decorate. Copying matters twice over: the live world must never be mutated by a
preview, and decoration runs on a worker thread where touching a live chunk
would race the server.

A margin of chunks is snapshotted around the requested region and then
discarded, because decoration reads its neighbours — a tree near an edge checks
whether it has room.

### Why the separation is enforced

`GroundPlane` carries a comment saying chunk previews must never import it, and
that is not bureaucratic. The flat-plane assumption is invisible in the output:
a natural preview built on it would render convincingly and be wrong about
every height-dependent decision. The two modes needed genuinely different
solutions — `FlatGenerator` versus `SessionGenerator` — which is good evidence
the boundary is real.

## Applying a datapack to chunk decoration

This is the subtlest part of the system.

Decoration resolves a biome's feature list through
`ChunkGenerator.getBiomeGenerationSettings`, and so does `BiomeFilter`, the
placement modifier that rejects a feature not belonging to the biome it landed
in. Both read a single function, which `ChunkGenerator`'s two-argument
constructor accepts. `SessionGenerator` delegates all terrain work to the real
generator and substitutes only that function, pointing it at the session's
registries — so the feature lists and their validation move together.

An earlier attempt swapped the biome objects returned by the level instead.
Decoration then iterated one registry set while the filter validated against
another, every feature failed its check, and previews came out silently empty:
500 decorated blocks became 0. **Registry sets must be swapped wholesale, never
half.** `ChunkPreviewLevel.getNoiseBiome` documents this at the point someone
would be tempted to undo it.

## Where files live

| Thing | Owner | Location |
|---|---|---|
| Your trees and replacers | Go app | your project folder |
| Replacer display mode | Go app | `tree-engine.replacers.json`, outside `data/` |
| Imported third-party datapacks | Go app | the server world's `datapacks/` |
| Server, Java, backend jar | Go app | `%LOCALAPPDATA%/TreeEngineLauncher` |
| Compiled registries | backend | memory only |

Project writes are atomic — a temp file and a rename — so an interrupted save
cannot leave a half-written tree for the backend to choke on.

Replacers are datapack authoring, not a runtime mechanism: a
`minecraft:random_selector` written under the replaced id's namespace, which
Minecraft loads in place of its own. The result works identically in a real
world and in the preview, and deleting the file restores vanilla.

## Server configuration

The managed server is headless and nobody joins it, so players, view distance
and structures are trimmed for boot time.

It must not be superflat, though. Natural previews decorate this world's
terrain, so its ground shape and biome layout are what the user sees. An
earlier configuration used `level-type=flat` — correct when every preview stood
on a fabricated plane, and silently wrong once chunk previews existed.
`serverprops.go` records this so it is not re-added as an optimisation.

## Version coupling

The backend is built against one Minecraft version at a time, currently 26.2 on
Mojang official mappings. Worldgen JSON schemas drift between versions —
`TreeConfiguration` gained a required `below_trunk_provider`, for one — so
nothing in this project hardcodes a feature config. Starting points and
templates are read from the live registry through
`GET /v1/registry/feature/{id}`, which is correct by construction rather than
by maintenance.
