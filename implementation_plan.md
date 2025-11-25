# Tree Engine Implementation Plan - Phase 1

## Phase 1: Core Functionality & Data Management

### Phase 1.1: Backend Tree CRUD System
- [x] **Update TreeDefinition**: Add `id`, `name`, `description` fields.
- [x] **Enhance TreeConfigManager**: Add `saveTree`, `deleteTree`, `listTrees` methods.
- [x] **Create API Endpoints**:
    - `GET /api/trees` (List)
    - `POST /api/trees` (Save/Update)
    - `DELETE /api/trees/{id}` (Delete)
- [x] **Register Handlers**: Hook up new endpoints in `WebEditorServer`.

### Phase 1.2: Frontend Tree Library
- [x] **Create Tree Browser UI**:
    - Add a sidebar tab for "Tree Library".
    - Implement a list view of available trees.
    - Add a search/filter bar.
- [x] **Connect to API**:
    - Fetch tree list on load.
    - Load selected tree into the editor form.
    - Implement "Save" button (POST to API).
    - Implement "Delete" button (DELETE to API).
- [x] **UI Refinements**:
    - Replace icon buttons with text buttons.
    - Add "Create New" workflow.
    - Contextual Save (Name field in editor).
    - Contextual Actions (Hide in Library, Disable Delete for new trees).
    - Auto-Regeneration (Sliders, Enter key, Biome select).

### Phase 1.3: Vanilla Tree Import
- [x] **Create Import API**:
    - `GET /api/vanilla_trees` (List available vanilla trees).
    - `POST /api/import_vanilla` (Import a vanilla tree as a custom config).
- [x] **Frontend Import UI**:
    - Add "Import Vanilla" button in Library.
    - Show modal/list of vanilla trees.
    - On selection, create a new tree config populated with vanilla settings.

### Phase 1.4: Advanced Generators
- [x] **Expand TreeDefinition**:
    - Add support for different `trunk_placer_type` (Straight, Forking, Mega, etc.).
    - Add support for different `foliage_placer_type` (Blob, Spruce, Pine, Jungle, etc.).
    - Add parameters for each placer type.
- [x] **Update Generator Logic**:
    - Refactor `WebEditorServer.generateTree` to use the configured placers.
    - Note: Currently uses foliage_height parameter; full placer type switching can be added incrementally.
- [x] **Update Frontend UI**:
    - Add dropdowns for Placer Types.
    - Add dynamic property fields based on selected placer.
