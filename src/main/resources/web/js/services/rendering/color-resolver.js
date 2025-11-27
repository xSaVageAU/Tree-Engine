// Color resolution utilities for rendering

function resolveLeafColor(blockId, biome) {
    if (FIXED_BLOCK_TINTS[blockId]) {
        return BABYLON.Color3.FromHexString(FIXED_BLOCK_TINTS[blockId]);
    }
    const hex = BIOME_COLORS[biome] || BIOME_COLORS['plains'];
    return BABYLON.Color3.FromHexString(hex);
}