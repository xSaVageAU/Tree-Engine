# Tree Engine

![Fabric](https://img.shields.io/badge/Fabric-1.21.10-cream?logo=fabric)
![License](https://img.shields.io/badge/License-MIT-green)

**Tree Engine** is a development tool for Minecraft that bridges the gap between in-game world generation and a modern desktop editor. It lets you design, visualize, and implement custom trees and world-generation features in real-time without restarting your server.

Unlike standard JSON editing, Tree Engine renders a **live 3D preview** by running the actual Minecraft generation logic in a "Phantom World" simulation, so what you see is exactly what will generate in the game - with game-accurate blocks, models, and textures via [deepslate](https://github.com/misode/deepslate).

## Project Structure

Tree Engine is two parts working together:

* **`mod/`** - A Fabric mod that runs inside Minecraft. It simulates tree generation in a "Phantom World" and exposes it over a local, token-authenticated HTTP API. Headless - it has no UI of its own.
* **`app/`** - A standalone desktop application (Go + [Wails](https://wails.io) + Svelte) that manages a Fabric server for you and provides the actual editor: browsing, creating, and editing trees with a live 3D preview.

## Key Features

* **🌲 Live 3D Preview:** A [deepslate](https://github.com/misode/deepslate)-based renderer reads real Minecraft block models, blockstates, and textures - the preview matches the game exactly, including biome-tinted leaves.
* **🔮 Phantom World Simulation:** The editor runs `ConfiguredFeature.generate()` in a virtual server world, handling all placement logic, decorators, and block states exactly as the game engine does.
* **🔥 Hot Reloading:** Changes are injected directly into the running game registry using reflection. **No server restarts required.**
* **🌲 Tree Replacers:** Override vanilla trees (Oak, Birch, Spruce, etc.) with a weighted or equal-chance pool of your own custom tree designs.
* **📦 Datapack-First Architecture:** All creations are saved as standard JSON files in a local datapack (`config/tree_engine/datapacks/`), making them easy to export and share.
* **📝 Monaco JSON Editor:** Integrated VS Code-style editor for fine-grained control over `ConfiguredFeature`/`PlacedFeature` configurations.
* **⚡ Performance Benchmarking:** Measure generation throughput for any tree configuration.
* **🚀 Zero-Setup Desktop App:** The standalone app downloads and manages Java, a Fabric server, Fabric API, and the mod automatically - no manual mod installation required.

## Getting Started

### Recommended: the standalone desktop app

The desktop app is the intended way to use Tree Engine - it manages the entire Minecraft server for you.

1. Clone this repository and build the app (see [Development](#development) below) - there's no distributed release yet, so this currently means building from source.
2. Launch the app, accept the Minecraft EULA, and click **Set Up & Start**. It downloads Java (if needed), a Fabric server, Fabric API, and installs the bundled mod automatically.
3. Once running, the app's editor connects directly to the mod - no browser, no manual auth token.
4. Click **+ Create New Tree** or **Import Vanilla Tree**, edit via the JSON panel, and watch the 3D preview regenerate live using the game's real generation logic.
5. **Save Tree** writes the datapack file and hot-reloads it into the running server.

### Manual: installing the mod into an existing server

If you'd rather run Tree Engine inside a server you already manage:

1. Install **Fabric Loader** for any Minecraft version 1.21.2 → 1.21.10.
2. Install **Fabric API**.
3. Drop `tree-engine.jar` into your `mods` folder.
4. Launch the server, then run `/tree_engine web start`.
5. The mod exposes a token-authenticated HTTP API on port 3000 (see **Commands** below) - point the desktop app's editor at it, or drive the API directly.

## Tree Replacers

Tree Engine can replace vanilla trees without complex biome modification:

1. In the editor, open **Tree Replacers**.
2. Click **+ Create New Replacer**.
3. Select a vanilla tree target (e.g., `minecraft:oak`).
4. Pick a **Weighted** pool (a default tree plus chance-based alternatives) or a **Simple** equal-chance pool of your custom trees.
5. Save.

*The mod automatically generates a `simple_random_selector` that intercepts the vanilla feature ID, allowing your custom trees to spawn naturally in the world.*

## Configuration & Resources

The mod creates a configuration folder at `config/tree_engine/`:

* **`config.json`**: Change the port or manage auth settings.
* **`datapacks/tree_engine_trees/`**: This is where your actual work is saved. Copy this folder into any world's `datapacks/` folder to ship your modpack.

## Commands

| Command | Description |
| :--- | :--- |
| `/tree_engine web start` | Starts the mod's HTTP API. |
| `/tree_engine web stop` | Stops the HTTP API. |
| `/tree_engine web status` | Displays the current port and status. |
| `/tree_engine reload` | Manually hot-reloads all trees and replacers from disk. |
| `/tree_engine web reload` | Restarts the HTTP listener (e.g. after a config change). |

## Development

* **Mod** (`mod/`): a standard Fabric Gradle project. `./gradlew build` produces `tree-engine.jar`.
* **App** (`app/`): a Wails v2 app. `wails dev` runs it with live reload; `wails build` produces a distributable binary. The frontend (`app/frontend/`) is Svelte 5 + TypeScript + Vite.
* After changing the mod, run `scripts/sync-mod-jar.ps1` to rebuild it and embed the fresh jar into the Go app.

## Technical Details

* **Mod backend:** Java (Fabric) using `com.sun.net.httpserver`, headless - API only, no bundled UI.
* **Registry Injection:** Uses `RegistryUtils` to access private fields in `ConfiguredFeature` and `TreeFeatureConfig`, modifying them in-place to allow runtime updates without breaking registry references.
* **Desktop app:** Go + Wails manages the Fabric server process and downloads vanilla Minecraft client assets (block models/blockstates/textures) at runtime for the renderer - never bundled, matching Mojang's redistribution terms.
* **Renderer:** [deepslate](https://github.com/misode/deepslate) - a data-driven renderer that reads real vanilla block models and blockstates rather than hardcoding per-block logic, so new block types render correctly with no extra code.

## Development Notice

This project is developed with significant assistance from AI coding tools (Google Gemini). While AI has been instrumental in writing much of the codebase, all code is reviewed, tested, and maintained by the project owner.

If you encounter any issues or have questions about the project, please feel free to open an issue on GitHub.

## License

This project is licensed under the **MIT License**.
