# Tree Engine Web Editor - Development Roadmap

## Vision
Transform the web editor into a comprehensive tree generation IDE with full CRUD operations, visual editing, hierarchy management, and real-time preview capabilities.

---

## Phase 1: Foundation & Data Management

### 1.1 Tree Configuration System
- [x] Basic tree generation API
- [x] Render engine with Minecraft-accurate lighting
- [ ] Tree config CRUD endpoints
  - `GET /api/trees` - List all trees
  - `GET /api/trees/{id}` - Get specific tree
  - `POST /api/trees` - Create new tree
  - `PUT /api/trees/{id}` - Update tree
  - `DELETE /api/trees/{id}` - Delete tree
- [ ] Vanilla tree importer
  - Parse Minecraft's configured features
  - Convert to custom config format
  - Preserve all properties (trunk placer, foliage placer, decorators)

### 1.2 UI - Tree Library
- [ ] Tree browser/gallery view
- [ ] Tree card components (thumbnail, name, stats)
- [ ] Search and filter functionality
- [ ] "New Tree" wizard
- [ ] Load/Save buttons with feedback

---

## Phase 2: Advanced Generator Controls

### 2.1 Trunk Placer System
- [ ] Backend: Support multiple trunk placer types
  - Straight trunk
  - Forking trunk
  - Fancy trunk (branching)
  - Mega trunk (2x2)
  - Giant trunk (3x3)
  - Bending trunk
- [ ] UI: Trunk placer selector with visual previews
- [ ] Per-placer configuration panels

### 2.2 Foliage Placer System
- [ ] Backend: Support multiple foliage placer types
  - Blob foliage
  - Spruce foliage
  - Pine foliage
  - Acacia foliage
  - Cherry foliage
  - Random spread
- [ ] UI: Foliage placer selector
- [ ] Radius, offset, height controls per type

### 2.3 Decorator System
- [ ] Backend: Decorator attachment system
  - Trunk decorators (vines, moss, cocoa)
  - Foliage decorators (hanging leaves, fruit)
  - Root decorators (exposed roots, mangrove props)
- [ ] UI: Decorator list with add/remove
- [ ] Decorator configuration panels

---

## Phase 3: Hierarchy & Relationships

### 3.1 Parent/Child System
- [ ] Backend: Tree inheritance model
  - Parent tree reference
  - Property override system
  - Variant generation
- [ ] UI: Hierarchy tree view
- [ ] Drag-and-drop tree organization
- [ ] "Create Variant" from existing tree

### 3.2 Biome Assignment
- [ ] Backend: Biome-to-tree mapping
  - Multiple trees per biome
  - Weight/probability system
- [ ] UI: Biome assignment interface
- [ ] Visual biome selector
- [ ] Spawn weight sliders

---

## Phase 4: Visual Editing Tools

### 4.1 Manual Block Editing
- [ ] Click-to-place block mode
- [ ] Block removal mode
- [ ] Block palette UI
- [ ] Undo/Redo stack (backend + frontend)

### 4.2 Advanced Editing
- [ ] Symmetry tools (mirror X/Z axis)
- [ ] Copy/Paste sections
- [ ] Selection box tool
- [ ] Brush sizes for foliage

### 4.3 Multi-Tree Preview
- [ ] Generate 5-10 variations simultaneously
- [ ] Grid layout view
- [ ] Comparison mode (side-by-side)
- [ ] Select favorite to keep

---

## Phase 5: Analysis & Validation

### 5.1 Statistics Panel
- [ ] Block count (total + by type)
- [ ] Bounding box dimensions (height/width)
- [ ] Generation time metrics
- [ ] Complexity score

### 5.2 Validation System
- [ ] Rule engine for warnings
  - Height limits for biomes
  - Missing required decorators
  - Invalid block combinations
- [ ] Visual warning indicators
- [ ] Validation report panel

---

## Phase 6: Export & Integration

### 6.1 Export Features
- [ ] Datapack export (vanilla-compatible)
- [ ] Schematic export (.nbt structure files)
- [ ] JSON config download
- [ ] Screenshot/GIF capture

### 6.2 Hot Reload
- [ ] Live sync with running game
- [ ] `/tree_engine sync` command
- [ ] Auto-refresh on save
- [ ] Change notification system

---

## Phase 7: UX Polish

### 7.1 Interface Improvements
- [ ] Tabbed layout (Generator | Hierarchy | Decorators | Export)
- [ ] Collapsible control sections
- [ ] Keyboard shortcuts
- [ ] Tooltips and help text
- [ ] Loading states and animations

### 7.2 Preset System
- [ ] Template library ("Tall Oak", "Dense Spruce")
- [ ] Quick-apply presets
- [ ] Save custom presets
- [ ] Share presets (export/import)

### 7.3 Developer Tools
- [ ] JSON viewer/editor
- [ ] Debug mode (show generation steps)
- [ ] Error console
- [ ] API documentation panel

---

## Technical Requirements

### Backend APIs Needed
```
GET    /api/trees              - List all trees
GET    /api/trees/{id}         - Get tree by ID
POST   /api/trees              - Create new tree
PUT    /api/trees/{id}         - Update tree
DELETE /api/trees/{id}         - Delete tree
GET    /api/trees/vanilla      - List vanilla trees
POST   /api/trees/import       - Import vanilla tree
GET    /api/generate           - Generate tree blocks (existing)
POST   /api/trees/{id}/export  - Export tree (datapack/schematic)
```

### Data Models
- **TreeConfig**: Full tree configuration
- **TrunkPlacer**: Type + specific config
- **FoliagePlacer**: Type + specific config
- **Decorator**: Type + placement rules
- **BiomeAssignment**: Biome + weight mapping

### Frontend Components
- TreeBrowser (gallery)
- TreeEditor (main workspace)
- PropertyPanel (controls)
- HierarchyView (tree structure)
- PreviewGrid (multi-tree view)
- ExportDialog (export options)

---

## Success Metrics
- [ ] Can load any vanilla tree
- [ ] Can create custom tree from scratch
- [ ] Can modify and save changes
- [ ] Can export to datapack
- [ ] Can manage tree families with inheritance
- [ ] Sub-second generation time for complex trees
- [ ] Intuitive UI requiring minimal documentation

---

## Future Considerations
- Collaborative editing (multi-user)
- Version history/git integration
- Community tree sharing platform
- AI-assisted tree generation
- Procedural variation algorithms
- Seasonal variants (spring/fall colors)
