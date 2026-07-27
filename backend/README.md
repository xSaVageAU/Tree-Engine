# Tree Engine Backend

A headless Minecraft generation service. It compiles datapacks handed to it
over HTTP and returns the blocks that generation produced.

It has no in-game presence: no commands, no mixins, no registry mutation, no
GUI. It boots a dedicated server purely to obtain a fully bootstrapped
registry. It writes nothing to disk and keeps nothing between requests beyond a
small in-memory cache.

## Running it

The server reads `config/tree-engine-backend.json` relative to its working
directory, once at startup:

| Field | Meaning |
|---|---|
| `port` | Loopback port to listen on |
| `token` | Bearer token every request must present |
| `workerThreads` | Size of the request worker pool (capped at 16) |
| `sessionLimit` | How many compiled datapacks to keep cached |

The desktop app writes this file before launching the server. **If `token` is
missing or blank the backend refuses to start**, rather than exposing an
unauthenticated generation API. The listener binds `127.0.0.1` only.

For local development, create `backend/run/config/tree-engine-backend.json`
yourself and use `./gradlew runServer`.

## Conventions

Every request needs `Authorization: Bearer <token>`. Failures return the
matching HTTP status with a JSON body:

```
{ "error": "Datapack failed to load", "detail": "data/…/oak.json: No key below_trunk_provider in …" }
```

`error` is the short summary; `detail` carries the actionable part — usually
the codec's complaint about a specific file, with the file named.

Blocks come back in one shape everywhere:

| Field | Meaning |
|---|---|
| `x`, `y`, `z` | Absolute block position |
| `name` | Block id, e.g. `minecraft:oak_log` |
| `properties` | Blockstate properties as strings; omitted when there are none |

## Sessions

A session is a compiled datapack held in memory. Previews reference one by id
so an editor re-rendering on every keystroke does not re-upload the pack.

**`POST /v1/session`** — body is `{"files": {<path>: <contents>}}`, where paths
are datapack-relative (`data/<namespace>/worldgen/configured_feature/x.json`).
Returns `sessionId`, `fileCount` and `cached`.

The id is a fingerprint of the content, so sending an unchanged datapack is a
cache hit rather than a recompile. Sessions are evicted by age (30 minutes) and
count; eviction is harmless because re-uploading yields the same id.

**`DELETE /v1/session/{id}`** — drops it early. Returns `{"removed": bool}`.

Anything outside `data/<namespace>/…` is rejected, as is any path containing
`..`.

## Previews

**`POST /v1/preview/tree`** — one feature, generated in isolation.

| Field | Meaning |
|---|---|
| `feature` | Inline feature JSON, *or* |
| `featureId` | An id to resolve from the registry |
| `sessionId` | Optional — omit for features that only reference vanilla |
| `biome` | Biome to report to the feature; defaults to plains |
| `seed` | Same seed gives the same tree |
| `includeGround` | Include the fabricated soil the tree grew on |

Returns `blocks`, `blockCount`, and `placed` — where `placed: false` means the
feature declined to generate (bad soil, not enough room). That is a real
outcome worth showing, not an error.

**`POST /v1/preview/chunk`** — real terrain, decorated with the session's
features.

| Field | Meaning |
|---|---|
| `sessionId` | Omit to preview plain vanilla generation |
| `chunkX`, `chunkZ` | Chunk to generate |
| `size` | Chunks across: 1 up to 6 (6×6). Capped at 36 chunks total |
| `radius` | Older form: 0 = one chunk, 1 = 3×3. Cannot express an even span |
| `seed` | Decoration seed |
| `decoratedOnly` | Return only what decoration added, not the terrain under it |
| `minY`, `maxY` | Explicit vertical window. Omit both for an auto fit |

Returns `blocks`, `blockCount`, `chunkCount`, `decoratedCount`, `minY`, `maxY`,
and `datapackApplied` — the last being false when no session was supplied, so
a client can never present vanilla output as though it reflected the user's
datapack.

**The vertical window matters.** A chunk spans y −64→320 and is overwhelmingly
underground stone; returning all of it is both slow and useless to look at.

The preview is cut at a flat height, `minY` if given and **y=50** otherwise —
below sea level (63) so shorelines survive, well above the deepslate
transition (~0) so the visible profile is terrain you would build on. Two earlier and
cleverer schemes are worth not repeating: fitting the window to the region's
surface, and a per-column crust following it. Both bounded the volume better
and both produced artefacts on sloped ground that read as missing terrain. A
single flat cut reads as a cross-section, which is honest and legible.

Above that cut, **every non-air block is reported**. A 3×3 at y 50–90 is around
59k blocks.

An earlier version dropped any block sealed in by six occluding neighbours, on
the reasoning that it contributes no visible face. That was true of the first
frame and false of the data: a mountain arrived as a hollow shell, and the rule
disagreed with the renderer about what "opaque" means — `canOcclude` here
against `isSolidRender` in the block flags — so a block could be dropped that
the renderer would have drawn faces for. It also made the renderer *slower*: a
surface block whose interior neighbour is missing has nothing to cull against,
so its inward face gets drawn into the dark. Removing the rule more than doubled
the block count and still cut the meshed geometry by ~10k quads.

Deciding what is visible is the renderer's job, per face, at draw time — the way
the game does it. This endpoint reports what generated.

Decoration reaching past the requested chunks is included, so a tree on a
chunk border is not sliced in half — bounded by the same window, since
underground decoration crosses borders as readily as a canopy does.

The `minY`/`maxY` in the response are the extent the output actually occupies.

Terrain comes from the running world, so it depends on that world's seed and
settings. The launcher configures a normal world; a superflat one would make
these previews meaningless.

## Registry

**`GET /v1/registry/features`** — configured feature ids. `?trees=true` narrows
to features that actually generate trees; `?sessionId=` scopes to a session.
Returns `features` and `count`.

**`GET /v1/registry/feature/{id}`** — that feature's datapack JSON, encoded
from the live registry. This is how the editor imports a vanilla tree and how
it seeds a new one, so starting configs are always correct for the running
Minecraft version instead of being literals someone has to maintain. The id may
contain slashes.

## Benchmark

**`POST /v1/benchmark`** — takes `feature` or `featureId`, plus optional
`sessionId` and `iterations` (default 1000, max 10000). Warms up first so JIT
cost does not dominate, then varies the seed per iteration. Returns
`iterations`, `totalMs`, `avgMs`, `treesPerSecond` and `avgBlocks`.

## Health

**`GET /v1/health`** — `status`, `minecraftVersion`, `backendVersion` and the
number of cached sessions.

## Layout

```
savage/tree_engine/
├─ TreeEngineBackend.java   entrypoint: lifecycle, route registration
├─ BackendConfig.java       the whole config, as a record
├─ api/                     HTTP server, auth, errors, request plumbing
├─ datapack/                in-memory packs, registry compilation, sessions
├─ registry/                read-only registry views
└─ preview/
   ├─ tree/                 single-tree mode — and nothing else
   └─ chunk/                natural chunk mode — and nothing else
```

The two preview packages share only `preview/BlockDto`. See
[../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md) for why that boundary is
enforced rather than encouraged.
