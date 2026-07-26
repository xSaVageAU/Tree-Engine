# Tree Engine desktop app

Go + [Wails](https://wails.io) + Svelte. This half owns everything the backend
deliberately does not: your project files, the managed Minecraft server, and
the editor UI.

## Running it

```
wails dev      # live reload
wails build    # package a binary
```

The backend jar is embedded at build time, and kept in step automatically: a
`preBuildHooks` entry in `wails.json` runs `scripts/sync-backend-jar.ps1` before
every Go build, including each `wails dev` hot restart. Change anything in
`backend/` and the next build embeds it - and `StartServer` rewrites the jar in
the managed instance on every start, so restarting the app is all it takes for
backend changes to go live.

Gradle is incremental and the jar is byte-reproducible, so when the backend is
unchanged the hook costs about a second and does not touch the embedded file.

`internal/assets/tree-engine.jar` is committed on purpose. It is `go:embed`-ed,
so `go build` fails without it, and keeping it in the repo means a clean clone
reaches a working binary with one `wails build`. It only changes when the
backend does.

`main.go` also does `//go:embed all:frontend/dist`, and dist is build output, so
a bare `go build` on a clean clone fails until the frontend has been built once.
`wails build`/`wails dev` create the directory themselves and CI runs vite
first, so this only bites raw Go tooling - an IDE opening the project before
anything has been built, typically.

## Layout

| Path | What it does |
|---|---|
| `app.go` | Wails lifecycle, setup and server control, status events |
| `project_api.go` | Project file operations exposed to the frontend |
| `internal/instance` | Paths, setup, project files, replacers, server config |
| `internal/serverproc` | Running the server process and capturing its output |
| `internal/javamgmt` | Downloading and locating a JRE |
| `internal/fabricmeta`, `internal/modrinth` | Fetching Fabric and Fabric API |
| `internal/mcassets` | Vanilla client assets for the renderer, cached per version |
| `internal/assets` | The embedded backend jar and its manifest |
| `frontend/src/lib` | Svelte components — editor, panels, modals |
| `frontend/src/renderer` | Preview rendering and the two API clients |

## The two clients

`renderer/mod-client.ts` talks HTTP to the backend: sessions, previews,
registry queries, benchmark. Generation only.

`renderer/project-client.ts` goes through Wails to Go: reading and writing
trees, placements and replacers. Persistence only. It also owns
`ensureSession`, which uploads the project to the backend and caches the
result, so an editor re-rendering on every keystroke does not re-read and
re-send the whole datapack.

Keep that split. The backend cannot store anything, and the Go side does not
generate anything.

## Managed instance

Everything the app downloads lives under `%LOCALAPPDATA%/TreeEngineLauncher` —
the server instance, a private JRE if the system has none suitable, and the
vanilla asset cache. The user never picks these paths.

Vanilla client assets (block models, blockstates, textures) are downloaded from
Mojang at runtime and cached per Minecraft version. They are never bundled, in
line with Mojang's redistribution terms.

The Minecraft EULA gate in the UI is a real agreement, not a formality — nothing
writes `eula.txt` before the user accepts it.
