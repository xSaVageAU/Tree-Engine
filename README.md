# Tree Engine

A Minecraft Fabric mod that provides a powerful web-based editor for creating, modifying, and managing custom tree generators with real-time 3D preview.

## Features

### Current
- ✅ **Web-Based 3D Editor** - Real-time Babylon.js renderer with Minecraft-accurate lighting
- ✅ **Custom Tree Generation** - Config-based tree system with JSON storage
- ✅ **Live Preview** - Instant visualization of tree changes
- ✅ **Biome Tinting** - Accurate leaf colors for all Minecraft biomes
- ✅ **Hot Reload** - `/tree_engine reload` command for rapid iteration
- ✅ **Texture Support** - Load textures from resource packs

### Planned (See [EDITOR_ROADMAP.md](EDITOR_ROADMAP.md))
- 🚧 Tree CRUD operations via web UI
- 🚧 Vanilla tree import system
- 🚧 Advanced trunk/foliage placers
- 🚧 Parent/child tree hierarchies
- 🚧 Visual block editing tools
- 🚧 Datapack export

## Installation

1. Download the latest release
2. Place in your `mods` folder
3. Launch Minecraft with Fabric
4. Access the editor at `http://localhost:3000`

## Usage

### Basic Tree Creation
1. Start Minecraft with the mod installed
2. Open `http://localhost:3000` in your browser
3. Adjust tree parameters in the sidebar
4. Click "Generate" to preview
5. Trees are saved to `config/tree_engine/trees/`

### Commands
- `/tree_engine reload` - Reload web server and configs

### Configuration
- **Trees**: `config/tree_engine/trees/*.json`
- **Web Files**: `config/tree_engine/web/index.html`
- **Textures**: `config/tree_engine/textures/`

## Development

### Building
```bash
./gradlew build
```

### Project Structure
```
src/main/java/savage/tree_engine/
├── config/          # Tree configuration management
├── registry/        # Virtual datapack system
├── web/             # HTTP server and API handlers
├── world/           # Phantom world for generation
└── command/         # In-game commands

src/main/resources/web/
└── index.html       # Web editor (exported to config on startup)
```

### Contributing
See [EDITOR_ROADMAP.md](EDITOR_ROADMAP.md) for planned features and implementation details.

## Technical Details

### Rendering Engine
- **Babylon.js** with custom Minecraft-style lighting
- **Hemispheric + Directional lighting** for proper face shading
- **SSAO** for smooth lighting effect
- **Thin instancing** for performance with large trees
- **Multi-material system** for log orientation

### Tree Generation
- Custom config-based system
- Support for multiple trunk/foliage placer types
- Decorator system for vines, moss, etc.
- Biome-aware leaf tinting

## License

This project is licensed under the CC0 1.0 License - see the [LICENSE](LICENSE) file for details.

## Credits

- Built with [Fabric](https://fabricmc.net/)
- 3D rendering powered by [Babylon.js](https://www.babylonjs.com/)
- Minecraft textures from [minecraft-assets](https://github.com/InventivetalentDev/minecraft-assets)
