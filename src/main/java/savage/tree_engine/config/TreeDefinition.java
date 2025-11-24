package savage.tree_engine.config;

import java.util.Optional;

public class TreeDefinition {
    public String parent;
    public String trunk_block;
    public String foliage_block;
    public Integer trunk_height_min;
    public Integer trunk_height_max;
    public Integer foliage_radius;
    public Integer foliage_offset;

    // Helper to merge child into this (parent)
    // Actually, it's better to merge parent into child, or have a method that returns a new merged definition.
    // Let's do: child.merge(parent) -> fills in missing nulls in child with parent's values.
    
    public void merge(TreeDefinition parent) {
        if (parent == null) return;

        if (this.trunk_block == null) this.trunk_block = parent.trunk_block;
        if (this.foliage_block == null) this.foliage_block = parent.foliage_block;
        if (this.trunk_height_min == null) this.trunk_height_min = parent.trunk_height_min;
        if (this.trunk_height_max == null) this.trunk_height_max = parent.trunk_height_max;
        if (this.foliage_radius == null) this.foliage_radius = parent.foliage_radius;
        if (this.foliage_offset == null) this.foliage_offset = parent.foliage_offset;
    }
}
