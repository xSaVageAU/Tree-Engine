package savage.tree_engine.config;

import java.util.Optional;

public class TreeDefinition {
    public String id;
    public String name;
    public String description;
    public String parent;
    public String trunk_block;
    public String foliage_block;
    public Integer trunk_height_min;
    public Integer trunk_height_max;
    public Integer foliage_radius;
    public Integer foliage_offset;
    
    // Trunk Placer Configuration
    public String trunk_placer_type; // "straight", "forking", "giant", "mega_jungle", "dark_oak", "cherry", "upwards_branching"
    public Integer trunk_base_height; // For some placers
    public Integer trunk_height_rand_a; // Random height variation
    public Integer trunk_height_rand_b; // Additional random height variation
    
    // Foliage Placer Configuration
    public String foliage_placer_type; // "blob", "spruce", "pine", "jungle", "acacia", "dark_oak", "mega_pine", "random_spread", "cherry"
    public Integer foliage_height; // Height of foliage
    public Integer foliage_layers; // Number of foliage layers (for some placers)

    // Helper to merge child into this (parent)
    // Actually, it's better to merge parent into child, or have a method that returns a new merged definition.
    // Let's do: child.merge(parent) -> fills in missing nulls in child with parent's values.
    
    public void merge(TreeDefinition parent) {
        if (parent == null) return;

        if (this.name == null) this.name = parent.name;
        if (this.description == null) this.description = parent.description;
        if (this.trunk_block == null) this.trunk_block = parent.trunk_block;
        if (this.foliage_block == null) this.foliage_block = parent.foliage_block;
        if (this.trunk_height_min == null) this.trunk_height_min = parent.trunk_height_min;
        if (this.trunk_height_max == null) this.trunk_height_max = parent.trunk_height_max;
        if (this.foliage_radius == null) this.foliage_radius = parent.foliage_radius;
        if (this.foliage_offset == null) this.foliage_offset = parent.foliage_offset;
        
        // Merge placer configuration
        if (this.trunk_placer_type == null) this.trunk_placer_type = parent.trunk_placer_type;
        if (this.trunk_base_height == null) this.trunk_base_height = parent.trunk_base_height;
        if (this.trunk_height_rand_a == null) this.trunk_height_rand_a = parent.trunk_height_rand_a;
        if (this.trunk_height_rand_b == null) this.trunk_height_rand_b = parent.trunk_height_rand_b;
        
        if (this.foliage_placer_type == null) this.foliage_placer_type = parent.foliage_placer_type;
        if (this.foliage_height == null) this.foliage_height = parent.foliage_height;
        if (this.foliage_layers == null) this.foliage_layers = parent.foliage_layers;
    }
}
