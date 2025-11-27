# Feature Specification: Modded Tree Overwrites

## 1. Executive Summary
This feature allows users to edit and replace existing trees from Vanilla Minecraft or other mods (e.g., Biomes O' Plenty) directly within the Tree Engine interface.

Instead of creating a "New Custom Tree" in the `tree_engine` namespace, this system utilizes **Data Pack Namespace Shadowing**. By saving a file with the exact same Namespace and ID as the target, Minecraft prioritizes our file over the original jar file.

## 2. Technical Architecture

### The "Shadowing" Mechanism
Minecraft loads resources based on priority. Tree Engine registers its internal data pack at `InsertionPosition.TOP`.
1.  **Original Mod**: Registers `biomesoplenty:mahogany` -> loads from `biomesoplenty.jar`.
2.  **Tree Engine**: Saves a file at `data/biomesoplenty/worldgen/configured_feature/mahogany.json`.
3.  **Result**: Minecraft ignores the jar file and loads the Tree Engine JSON instead.

### File Structure Comparison

**A. Custom Tree (Current Behavior)**
Used for creating brand new trees that spawn via the Tree Replacer system.
```text
config/tree_engine/datapacks/tree_engine_trees/
└── data/
    └── tree_engine/              <-- Fixed Namespace
        └── worldgen/
            └── configured_feature/
                └── my_custom_pine.json
```

**B. Overwrite (New Behavior)**
Used to replace an existing tree definition in the world.
```text
config/tree_engine/datapacks/tree_engine_trees/
└── data/
    └── minecraft/                <-- Dynamic Namespace (Shadows Vanilla)
    │   └── worldgen/
    │       └── configured_feature/
    │           └── oak.json
    └── biomesoplenty/            <-- Dynamic Namespace (Shadows Mod)
        └── worldgen/
            └── configured_feature/
                └── mahogany.json
```

## 3. Backend Implementation

### `TreeApiHandler.java` Logic Update
The save handler must stop enforcing the `tree_engine` namespace and instead parse the incoming ID.

**Pseudocode Logic:**
1.  **Parse Request**: Get ID `minecraft:oak` or `biomesoplenty:mahogany`.
2.  **Determine Target**:
    *   Namespace: `minecraft` / `biomesoplenty`
    *   Path: `oak` / `mahogany`
3.  **Resolve Directory**:
    `datapack_root + "/data/" + namespace + "/worldgen/configured_feature/"`
4.  **Write File**: Save the JSON config.
5.  **PlacedFeature Logic**:
    *   **If Namespace == `tree_engine`**: Generate a `placed_feature` so the custom tree can be spawned.
    *   **If Namespace != `tree_engine` (Overwrite)**: **SKIP** `placed_feature` generation. The original mod's placement rules already exist and will automatically point to our new file.

## 4. Frontend Implementation

### UI Workflow (`tree-manager.js`)
1.  **Import Modal**: Add a new button **"Overwrite"** next to "Import".
2.  **State Initialization**:
    *   Fetch raw JSON from `/api/vanilla_tree/{id}`.
    *   **Critical**: Store `selectedTreeId` as the full ID (e.g., `minecraft:oak`).
    *   **Critical**: Lock the "Tree Name" input field to prevent ID changes.
3.  **Save Action**:
    *   Send `POST` request to `/api/trees/minecraft:oak`.
    *   Backend handles the directory routing based on the colon separator.

### Deletion Logic
When "Delete" is clicked on an Overwrite:
1.  Frontend sends `DELETE /api/trees/minecraft:oak`.
2.  Backend deletes the file in `.../data/minecraft/.../oak.json`.
3.  **Result**: The "Shadow" is removed. On the next `/reload`, Minecraft falls back to the original Vanilla definition.

## 5. Benefits
*   **High Compatibility**: Works with any mod using standard Configured Features.
*   **Non-Destructive**: Does not modify the original mod jars.
*   **Safe**: No Mixins required for the logic; strictly relies on vanilla Data Pack behavior.
*   **Clean Uninstallation**: Deleting the generated `tree_engine_trees` folder reverts all changes instantly.