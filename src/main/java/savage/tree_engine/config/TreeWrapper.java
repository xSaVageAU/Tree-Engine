package savage.tree_engine.config;

import com.google.gson.JsonElement;

/**
 * Wrapper for tree definitions that embeds native Minecraft tree feature JSON.
 * Replaces the flat TreeDefinition POJO with a data-driven approach.
 */
public class TreeWrapper {
    /**
     * Unique identifier for this tree (e.g., "my_custom_oak")
     */
    public String id;
    
    /**
     * Display name shown in the UI (e.g., "My Custom Oak")
     */
    public String name;
    
    /**
     * User-provided description of the tree
     */
    public String description;
    
    /**
     * Optional namespace for multi-pack support (e.g., "mytrees")
     */
    public String namespace;
    
    /**
     * Target Minecraft version (e.g., "1.21.10")
     */
    public String version;
    
    /**
     * Feature type - always "minecraft:tree" for tree features
     */
    public String type = "minecraft:tree";
    
    /**
     * Raw Minecraft tree feature configuration as JSON.
     * This is the exact structure that Minecraft expects, containing:
     * - trunk_provider (BlockStateProvider)
     * - foliage_provider (BlockStateProvider)
     * - trunk_placer (TrunkPlacer)
     * - foliage_placer (FoliagePlacer)
     * - minimum_size (TreeFeatureSize)
     * - decorators (array)
     * - optional: dirt_provider, root_placer, ignore_vines, force_dirt
     */
    public JsonElement config;
    
    /**
     * Default constructor
     */
    public TreeWrapper() {
    }
    
    /**
     * Create a wrapper with basic metadata
     */
    public TreeWrapper(String id, String name, String description, JsonElement config) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.config = config;
    }
}
