// Configuration constants
// Configuration constants
const SKY_COLOR = (typeof BABYLON !== 'undefined') ? new BABYLON.Color3.FromHexString("#8cb6fc") : null;

// Wiki Data
const BIOME_COLORS = {
    'plains': '#77AB2F', 'forest': '#59AE30', 'birch_forest': '#6BA941',
    'jungle': '#30BB0B', 'sparse_jungle': '#3EB80F', 'swamp': '#6A7039',
    'mangrove': '#8DB127', 'desert': '#AEA42A', 'badlands': '#9E814D',
    'snowy': '#60A17B', 'taiga': '#68A464', 'meadow': '#63A948',
    'mushroom': '#2BBB0F', 'pale_garden': '#878D76', 'cherry': '#B6DB61'
};

const FIXED_BLOCK_TINTS = {
    'minecraft:spruce_leaves': '#619961', 'minecraft:birch_leaves': '#80A755',
    'minecraft:cherry_leaves': '#FFFFFF', 'minecraft:azalea_leaves': '#FFFFFF',
    'minecraft:flowering_azalea_leaves': '#FFFFFF', 'minecraft:pale_oak_leaves': '#FFFFFF',
    'minecraft:pale_oak_leaves_hanging': '#FFFFFF'
};

let scene, camera, engine, shadowGenerator;
let masterMeshes = {};
let autoRotate = false;