// Service for rendering complex block models (cross, wall, cocoa, etc.)
// This file delegates to specialized renderers for each block type

const ComplexBlockRenderer = {
    // Mapping of block IDs to their model type
    MODEL_TYPES: {
        // Cross models (flowers, saplings, fungi, etc.)
        'dandelion': 'cross',
        'poppy': 'cross',
        'blue_orchid': 'cross',
        'allium': 'cross',
        'azure_bluet': 'cross',
        'red_tulip': 'cross',
        'orange_tulip': 'cross',
        'white_tulip': 'cross',
        'pink_tulip': 'cross',
        'oxeye_daisy': 'cross',
        'cornflower': 'cross',
        'lily_of_the_valley': 'cross',
        'wither_rose': 'cross',
        'brown_mushroom': 'cross',
        'red_mushroom': 'cross',
        'crimson_fungus': 'cross',
        'warped_fungus': 'cross',
        'grass': 'cross',
        'fern': 'cross',
        'dead_bush': 'cross',
        'seagrass': 'cross',
        'crimson_roots': 'cross',
        'warped_roots': 'cross',
        'nether_sprouts': 'cross',
        'oak_sapling': 'cross',
        'spruce_sapling': 'cross',
        'birch_sapling': 'cross',
        'jungle_sapling': 'cross',
        'acacia_sapling': 'cross',
        'dark_oak_sapling': 'cross',
        'mangrove_propagule': 'cross',
        'cherry_sapling': 'cross',
        'azalea': 'cross',
        'flowering_azalea': 'cross',
        'sugar_cane': 'cross',

        // Wall models (vines, ladders, etc.)
        'vine': 'wall',
        'ladder': 'wall',
        'glow_lichen': 'wall',
        'sculk_vein': 'wall',

        // Custom models
        'cocoa': 'cocoa'
    },

    render(blocks, fullBlockName, blockId, texturePath, biome) {
        const modelType = this.MODEL_TYPES[blockId];

        if (modelType === 'cross') {
            renderCrossBlock(blocks, blockId, texturePath);
        } else if (modelType === 'wall') {
            renderWallBlock(blocks, blockId, texturePath, biome);
        } else if (modelType === 'cocoa') {
            renderCocoaBlock(blocks, blockId, texturePath);
        } else {
            console.warn(`Unknown complex model type for ${blockId}`);
        }
    }
};
