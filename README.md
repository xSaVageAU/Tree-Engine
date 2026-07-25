# Tree Engine

![Minecraft](https://img.shields.io/badge/Minecraft-26.2-cream)
![Fabric](https://img.shields.io/badge/Fabric-0.19.3-cream?logo=fabric)
![License](https://img.shields.io/badge/License-MIT-green)

A desktop editor for Minecraft's world-generation trees. You edit the datapack
JSON; it shows you what actually grows, rendered from a real Minecraft server
running the real generation code.

The point is that the preview is not an approximation. There is no
reimplementation of trunk placers or foliage shapes anywhere in this project —
a headless Minecraft server generates the tree and reports the blocks it
placed.

## The two halves

**`backend/`** — a Fabric mod that is a generation service, not a game feature.
It has no commands, no mixins, no GUI, and nobody ever joins it. It boots a
dedicated server to get a fully loaded registry, then answers HTTP requests:
*here is a datapack and something to generate, send back the blocks.* It owns
no files and persists nothing.

**`app/`** — a Go + [Wails](https://wails.io) + Svelte desktop app. It manages
the server (downloading Java, Fabric and the backend jar as needed), owns your
project files on disk, and provides the editor and 3D preview.

## Preview modes

**Single tree** *(default)* — one feature, generated in isolation on a
fabricated soil plane. Answers "what does this config produce", with no terrain
or neighbours in the way.

**Natural chunks** — real terrain from the running world, decorated with your
datapack's features. Answers "what will this look like in game". Reachable in
the API today; it does not have a UI yet.

The two share no code beyond the block format they both emit. That separation
is deliberate: the fabricated ground the single-tree mode depends on would make
natural previews quietly wrong.

## Rendering

The preview uses [deepslate](https://github.com/misode/deepslate), which reads
real vanilla block models, blockstates and textures, so new block types render
correctly without per-block code. Those assets are downloaded from Mojang at
runtime and cached — never bundled, in line with Mojang's redistribution terms.

## Getting started

There is no released binary yet, so this means building from source.

1. Build the backend jar and embed it into the app:
   ```
   pwsh scripts/sync-backend-jar.ps1
   ```
2. Run the app:
   ```
   cd app && wails dev
   ```
3. Accept the Minecraft EULA in the UI, then **Set Up & Start**. The app
   downloads Java (if needed), a Fabric server and Fabric API, and installs the
   backend.
4. Open a project folder — any folder; it is scaffolded into a datapack if it
   isn't one already.
5. **New Tree**, edit the JSON, watch the preview regenerate.

Your project is a plain datapack. Copy it into a world's `datapacks/` folder
and it works, with no part of Tree Engine involved.

## Tree replacers

A replacer makes a vanilla tree generate one of yours instead. It works by
datapack shadowing: Tree Engine writes a `minecraft:random_selector` at, say,
`data/minecraft/worldgen/configured_feature/oak.json`, and Minecraft loads that
in place of its own definition.

There is no runtime trickery involved — the result is a file that behaves the
same in your world as it does in the preview, and deleting it restores vanilla.

## Development

| | |
|---|---|
| Backend | `cd backend && ./gradlew build` — needs JDK 25 |
| Backend alone | `cd backend && ./gradlew runServer` (needs a config file, see [backend/README.md](backend/README.md)) |
| App | `cd app && wails dev` for live reload, `wails build` to package |
| Frontend checks | `cd app/frontend && npx svelte-check` |
| Re-embed the backend | `pwsh scripts/sync-backend-jar.ps1` after any backend change |

Further reading: [backend/README.md](backend/README.md) for the HTTP API,
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for how the pieces fit and why,
and [docs/ROADMAP.md](docs/ROADMAP.md) for what is next.

## A note on how this is built

Much of this codebase is written with AI assistance. Everything is reviewed and
maintained by the project owner. If something looks wrong, please open an issue.

## License

MIT.
