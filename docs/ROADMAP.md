# Tree Engine 2.0 - Project Roadmap

## 1. Project Status
**Current State:** Proof of Concept / Alpha
The project is currently a functional prototype with a Java-based backend (Fabric Mod) and a Vanilla JavaScript web editor. It demonstrates the core capability of editing Minecraft tree features via a web interface and hot-reloading them in-game.

## 2. Current Accomplishments (Features)
### Backend (Java/Fabric)

- [x] **Datapack-First Architecture**: Trees are stored as standard Minecraft datapacks in `config/tree_engine/datapacks`.
- [x] **Web Server Integration**: Embedded HTTP server to serve the editor and handle API requests.
- [x] **Tree Replacer System**: Logic to replace vanilla trees with custom configured trees at runtime.
- [x] **PhantomWorld**: A fake world object that is used to generate trees in.
- [x] **Hot Reloading**: Basic support for reloading tree configurations without restarting the game.
- [x] **Texture Pack Support**: API to list and serve texture packs for the frontend.
- [x] **Performance Profiler**: Measure and display performance metrics for tree generation.
- [x] **Multi-Version Support**: Supporting multiple Minecraft versions simultaneously (`1.21.2` -> `1.21.10`).

### Frontend (Vanilla JS)

- [x] **Tree Browser**: UI to list and select configured trees.
- [x] **Visual Preview**: Basic rendering/visualization of tree structures (3D).
- [x] **Import/Export**: Ability to import vanilla trees and save modifications.
- [x] **Texture Pack Selector**: Dropdown to switch between available resource packs for preview.
- [x] **Monaco Editor**: Basic Monaco editor integration for JSON editing.
- [x] **JSON editing for ConfiguredFeatures**: Allow editing of ConfiguredFeatures directly in the frontend.

## 3. Immediate Roadmap (Polish & Fixes)
*Goal: Stabilize the current PoC before major rewrites.*
- [ ] **Code Cleanup**: Remove unused code and ensure strict separation of concerns.
- [ ] **UI/UX Polish**: Improve error messages, loading states, and layout.
- [ ] **Modded Tree Shadowing**: Allow saving imported modded trees as a "shadow", which takes priority over the original pack's tree and replaces it. 
- [ ] **JSON editing for PlacedFeatures**: Allow editing of PlacedFeatures directly in the frontend.
- [ ] **Finish renderer**: Support all block types and features.


## 4. Future Roadmap (Major Refactors)
*Goal: Modernize the stack and expand capabilities.*
- [ ] **Dynamic Form Editor**: Include a from editor similar to or using Misode's form editor.

### Frontend Rewrite
*goal: modernize the frontend stack. Make it easier to maintain and expand.*
*To Be Decided*

### Advanced Features
- [ ] **Biome Integration**: Visual biome selector to assign tree generation to specific biomes easily.
- [ ] **Performance Optimization**: Optimize the web server for handling large datapacks.

## 5. Out of Scope / Ideas (Backlog)
- **In-Game 3D Editor**: Fully interactive in-game GUI (likely out of scope due to complexity vs Web UI).
- **Wails Standalone Version**: A version of the editor that can be run as a standalone application. Would require a fabric server wrapper.
