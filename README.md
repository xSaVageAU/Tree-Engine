# Tree Engine

![Fabric](https://img.shields.io/badge/Fabric-1.21.10-cream?logo=fabric)
![License](https://img.shields.io/badge/License-CC0-green)

**Tree Engine** is a powerful development tool for Minecraft that bridges the gap between in-game world generation and modern web interfaces. It allows you to design, visualize, and implement custom trees and world-generation features in real-time without restarting your server.

Unlike standard JSON editing, Tree Engine provides a **3D Web Editor** that runs the actual Minecraft generation logic in a "Phantom World" simulation, ensuring that what you see in the browser is exactly what will generate in the game.

https://github.com/user-attachments/assets/dadb7aad-cf3f-4de2-a6cc-ff38f6b9474e
## Key Features

*   **🌐 Web-Based Voxel Editor:** A locally hosted web interface (default port 3000) using **Babylon.js** for high-performance 3D rendering.
*   **🔥 Hot Reloading:** Changes made in the web editor are injected directly into the running game registry using reflection. **No server restarts required.**
*   **🌲 Tree Replacers:** A built-in system to override vanilla trees (e.g., Oak, Birch, Spruce) with a weighted pool of your own custom tree designs.
*   **🔮 Phantom World Simulation:** The editor runs `ConfiguredFeature.generate()` in a virtual server world, handling all placement logic, decorators, and block states exactly as the game engine does.
*   **📦 Datapack-First Architecture:** All creations are saved as standard JSON files in a local datapack (`config/tree_engine/datapacks/`), making them easy to export and share.
*   **🎨 Texture Pack Support:** Drop resource packs into the config folder to visualize trees with your custom textures in the browser.
*   **📝 Monaco JSON Editor:** Integrated VS Code-style editor for power users who want fine-grained control over feature configurations.

## Installation

1.  Install **Fabric Loader** for Minecraft 1.21.10.
2.  Install **Fabric API**.
3.  Drop the `tree-engine.jar` into your `mods` folder.
4.  Launch the game/server.

## Getting Started

### 1. Start the Web Server
Once in-game or via the server console, run:
```mcfunction
/tree_engine web start
```

### 2. Authenticate
For security, the editor is protected by a token. When you start the web server, look at your **game logs/console**:
```text
============================================================
WEB EDITOR AUTHENTICATION TOKEN:
a1b2c3d4e5... (your unique token)
============================================================
```
1.  Open your browser to `http://localhost:3000`.
2.  Paste the token into the "Auth Token" field in the top-left corner and click **Save**.

### 3. Create & Visualize
1.  Click **+ Create New Tree**.
2.  Adjust parameters using the UI or the JSON editor.
3.  The 3D preview will automatically regenerate using the game's engine.
4.  Click **Save Tree** to write the file and hot-reload it into the game.

## Tree Replacers

Tree Engine allows you to replace vanilla trees without complex biome modification.

1.  In the Web Editor, switch to the **Tree Replacers** tab.
2.  Click **Create New Replacer**.
3.  Select a vanilla tree target (e.g., `minecraft:oak`).
4.  Select one or more of your **Custom Trees** to add to the replacement pool.
5.  Save.

*The mod automatically generates a `simple_random_selector` that intercepts the vanilla feature ID, allowing your custom trees to spawn naturally in the world.*

## Configuration & Resources

The mod creates a configuration folder at `config/tree_engine/`:

*   **`config.json`**: Change the port, toggle dev mode, or manage auth settings.
*   **`textures/`**: Drop resource packs (zip or folder) here. Select them in the Web Editor to view your trees with correct textures.
*   **`datapacks/tree_engine_trees/`**: This is where your actual work is saved. You can copy this folder to any world's `datapacks/` folder to ship your modpack.

## Commands

| Command | Description |
| :--- | :--- |
| `/tree_engine web start` | Starts the web editor server. |
| `/tree_engine web stop` | Stops the web editor server. |
| `/tree_engine web status` | Displays the current port and status. |
| `/tree_engine reload` | Manually hot-reloads all trees and replacers from disk. |
| `/tree_engine web reload` | Reloads the web server frontend files (for dev mode). |

## Technical Details

*   **Backend:** Java (Fabric) using `com.sun.net.httpserver`.
*   **Frontend:** Vanilla JS + Babylon.js + Monaco Editor.
*   **Registry Injection:** Uses `RegistryUtils` to access private fields in `ConfiguredFeature` and `TreeFeatureConfig`, modifying them in-place to allow runtime updates without breaking registry references.

## Development Notice

This project is developed with significant assistance from AI coding tools (Google Gemini). While AI has been instrumental in writing much of the codebase, all code is reviewed, tested, and maintained by the project owner. 

If you encounter any issues or have questions about the project, please feel free to open an issue on GitHub.

## License

This project is licensed under **CC0-1.0**.