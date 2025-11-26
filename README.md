# Tree Engine

A Minecraft Fabric mod that provides a powerful web-based editor for creating, modifying, and managing custom tree generators with real-time 3D preview.

https://github.com/user-attachments/assets/fef560ee-a708-45df-a56d-d9fe799a13fd

## Features

### 🌳 Web-Based Tree Editor
- **Real-time 3D Preview** - Babylon.js renderer with Minecraft-accurate lighting and shadows
- **Monaco Editor** - Professional JSON editor with syntax highlighting and auto-completion
- **Auto-Regeneration** - Live preview updates 500ms after you stop typing
- **Overlay Design** - Monaco editor slides up from bottom, keeping the 3D view visible
- **Biome Tinting** - Accurate leaf colors for all 15+ Minecraft biomes
- **Resource Pack Support** - Load textures from any Minecraft resource pack

### 📚 Tree Library Management
- **Tree Browser** - Visual library of all custom trees with search and filtering
- **Import Vanilla Trees** - One-click import from Minecraft's built-in trees (or modded trees)
- **CRUD Operations** - Create, edit, save, and delete trees via web UI
- **Settings Panel** - Clean interface for tree metadata (name, description, biome preview)
- **Hot Reload** - `/tree_engine reload` command for rapid iteration

### 🎨 Accurate Rendering
- **1:1 Preview** - What you see is what you get in-game
- **Log Rotations** - Correctly renders horizontal logs (axis x/z)
- **Multi-material System** - Proper log textures (side + top)
- **Thin Instancing** - Performant rendering of large trees (1000+ blocks)
- **Minecraft-style Lighting** - Hemispheric + directional lights + SSAO

## Installation

1. Download the latest release
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

## Future Improvements

The editor is production-ready for manual JSON editing with live preview. Planned enhancements include:

### UI Improvements
- **Placer Selectors** - Dropdown menus for trunk/foliage placer types instead of manual JSON editing
- **Decorator Management** - Visual interface to add/remove decorators (vines, beehives, cocoa, etc.)
- **Validation Panel** - Real-time warnings for invalid configurations or biome-specific issues
- **Statistics Display** - Show block counts, tree dimensions, and complexity metrics

### Advanced Features
- **Visual Block Editing** - Click-to-place blocks directly in the 3D view
- **Tree Hierarchy** - Parent/child relationships for tree variants and inheritance
- **Datapack Export** - Generate vanilla-compatible datapack files
- **Preset Library** - Pre-built templates for common tree types
- **Multi-tree Preview** - Generate and compare multiple variations side-by-side

See [EDITOR_ROADMAP.md](EDITOR_ROADMAP.md) for detailed implementation plans.

## Credits

- Built with [Fabric](https://fabricmc.net/)
- 3D rendering powered by [Babylon.js](https://www.babylonjs.com/)
- Minecraft textures from [minecraft-assets](https://github.com/InventivetalentDev/minecraft-assets)
