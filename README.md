# Tree Engine

A Minecraft Fabric mod that provides a powerful web-based editor for creating, modifying, and managing custom tree generators with real-time 3D preview.

https://github.com/user-attachments/assets/fef560ee-a708-45df-a56d-d9fe799a13fd

## Features

### 🌳 Web-Based Tree Editor
- **Real-time 3D Preview** - Babylon.js renderer with Minecraft-accurate lighting and shadows
- **Dynamic Form Builder** - Schema-driven UI that adapts to Minecraft's tree configuration format
- **Dual Edit Modes** - Switch between visual form editor and raw JSON editing
- **Live Updates** - Changes instantly reflected in the 3D preview
- **Biome Tinting** - Accurate leaf colors for all 15+ Minecraft biomes
- **Resource Pack Support** - Load textures from any Minecraft resource pack

### 📚 Tree Library Management
- **Tree Browser** - Visual library of all custom trees with search
- **Import Vanilla Trees** - One-click import from Minecraft's built-in trees
- **CRUD Operations** - Create, edit, duplicate, and delete trees via web UI
- **Metadata System** - Name, description, and tags for organization
- **Hot Reload** - `/tree_engine reload` command for rapid iteration

### 🎨 Accurate Rendering
- **1:1 Preview** - What you see is what you get in-game
- **Log Rotations** - Correctly renders horizontal logs (axis x/z)
- **Multi-material System** - Proper log textures (side + top)
- **Thin Instancing** - Performant rendering of large trees (1000+ blocks)
- **Minecraft-style Lighting** - Hemispheric + directional lights + SSAO

### Planned Features
See [EDITOR_ROADMAP.md](EDITOR_ROADMAP.md) for upcoming features:
- Advanced trunk/foliage placer support
- Visual block editing tools
- Parent/child tree hierarchies
- Datapack export functionality

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
