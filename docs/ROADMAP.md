# Tree Engine 2.0 - Project Roadmap

## 1. Project Status

**Current State:** Beta - the "2.0 overhaul" described below is implemented.

The project is now two parts: a headless Fabric mod (Java) that simulates tree generation and exposes it over a local HTTP API, and a standalone desktop app (Go/Wails + Svelte) that manages the Minecraft server and provides the actual editor UI. The old single-mod, Babylon.js-in-a-browser architecture has been fully replaced.

## 2. Current Accomplishments (Features)

### Backend (Java/Fabric mod)

- [x] **Datapack-First Architecture**: Trees are stored as standard Minecraft datapacks in `config/tree_engine/datapacks`.
- [x] **Headless HTTP API**: Embedded HTTP server exposing tree/replacer CRUD, generation, and benchmarking. No bundled UI - the mod is API-only.
- [x] **Tree Replacer System**: Logic to replace vanilla trees with custom configured trees at runtime (weighted or simple pools).
- [x] **PhantomWorld**: A fake world object used to generate trees in, without touching the real world.
- [x] **Hot Reloading**: Reflection-based injection of saved trees/replacers into the running registry - no restart required.
- [x] **Performance Benchmarking**: `/api/benchmark` measures generation throughput for a given tree config.
- [x] **Multi-Version Support**: Supporting multiple Minecraft versions simultaneously (`1.21.2` → `1.21.10`).

### Desktop App (Go/Wails)

- [x] **Zero-setup instance management**: Downloads Java, a Fabric server, Fabric API, and installs the bundled mod automatically.
- [x] **Vanilla asset provisioning**: Downloads and caches the vanilla Minecraft client's block models/blockstates/textures directly from Mojang at runtime (never bundled), keyed by Minecraft version.

### Frontend (Svelte SPA)

- [x] **Data-driven 3D renderer**: [deepslate](https://github.com/misode/deepslate) reads real vanilla block models/blockstates instead of hardcoded per-block-type logic - new block types render correctly for free.
- [x] **Biome tint selector**: Correct per-biome foliage/grass tinting in the live preview.
- [x] **Tree Browser**: List, search, select, create, and import (from vanilla) trees.
- [x] **Tree Replacer UI**: Create/edit/delete weighted or simple replacer pools.
- [x] **Monaco JSON Editor**: Two-way bound Tree Config / Placement Rules editing.
- [x] **Benchmark UI**: Trigger and view benchmark results in-app.

## 3. Backlog

- [ ] **Modded Tree Shadowing**: Save an imported vanilla/modded tree as a "shadow" that takes priority over the original and replaces it in-place (see `docs/roadmap-ideas/modded-tree-overwrites.md`).
- [ ] **Dynamic Form Editor**: A schema-driven form UI as an alternative to raw JSON editing.
  - Investigated during the 2.0 overhaul: no existing library covers this - Misode's schema-form tooling (`@mcschema/core`) is ~2 years stale and its actual Minecraft schema *definitions* were never published as a reusable library. Building this well means hand-authoring a purpose-built form for `TreeFeatureConfig` and friends, not adopting an off-the-shelf engine.
- [ ] **Resource pack overlay**: Layer a custom resource pack's textures on top of vanilla in the live preview.
  - Attempted during the 2.0 overhaul (mod-served pack listing/serving + renderer-side overlay, plus Modrinth pack search/download) but reverted: higher-resolution packs (64x/128x) didn't scale into the atlas correctly and the feature was creating more friction than value. Vanilla-only rendering is the current baseline; worth retrying if there's real demand.
- [ ] **Performance Optimization**: Optimize the API server for handling very large datapacks.

## 4. Out of Scope / Ideas

- **In-Game 3D Editor**: Fully interactive in-game GUI (likely out of scope due to complexity vs. the desktop app).
