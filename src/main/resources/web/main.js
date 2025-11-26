// --- CONFIGURATION ---
const SKY_COLOR = new BABYLON.Color3.FromHexString("#8cb6fc");

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

window.addEventListener('DOMContentLoaded', () => {
    initBabylon();
    setupUI();
    // Initial load handled by tree browser
});

function initBabylon() {
    const canvas = document.getElementById('renderCanvas');
    engine = new BABYLON.Engine(canvas, true, { stencil: true, preserveDrawingBuffer: true });

    scene = new BABYLON.Scene(engine);
    scene.clearColor = SKY_COLOR;
    scene.ambientColor = new BABYLON.Color3(0.5, 0.5, 0.5);

    scene.fogMode = BABYLON.Scene.FOGMODE_EXP2;
    scene.fogDensity = 0.008;
    scene.fogColor = SKY_COLOR;

    camera = new BABYLON.ArcRotateCamera("camera", -Math.PI / 2, Math.PI / 3, 15, new BABYLON.Vector3(0, 5, 0), scene);
    camera.attachControl(canvas, true);
    camera.wheelPrecision = 50;
    camera.lowerRadiusLimit = 2;
    camera.upperRadiusLimit = 50;

    // --- MINECRAFT-STYLE LIGHTING ENGINE ---

    // 1. Hemispheric Light: Handles Top vs Bottom brightness
    const hemiLight = new BABYLON.HemisphericLight("hemi", new BABYLON.Vector3(0, 1, 0), scene);
    hemiLight.intensity = 0.8;
    hemiLight.diffuse = new BABYLON.Color3(1, 1, 1);
    hemiLight.groundColor = new BABYLON.Color3(0.3, 0.3, 0.3); // Darker undersides

    // 2. Main "Sun" Light: Provides shading for sides (East/North differentiation)
    const sunLight = new BABYLON.DirectionalLight("sun", new BABYLON.Vector3(-0.6, -1, -0.4), scene);
    sunLight.position = new BABYLON.Vector3(20, 50, 20);
    sunLight.intensity = 0.7;

    // 3. Shadow Generator (Soft "Blocky" Shadows)
    shadowGenerator = new BABYLON.ShadowGenerator(2048, sunLight);
    shadowGenerator.usePercentageCloserFiltering = true;
    shadowGenerator.filteringQuality = BABYLON.ShadowGenerator.QUALITY_HIGH;
    shadowGenerator.setDarkness(0.3);
    shadowGenerator.bias = 0.0001;

    // 4. SSAO (Screen Space Ambient Occlusion) = "Smooth Lighting"
    try {
        const ssao = new BABYLON.SSAO2RenderingPipeline("ssao", scene, 0.75, [camera]);
        ssao.radius = 2.0;
        ssao.totalStrength = 1.3;
        ssao.base = 0.2;
        ssao.expensiveBlur = true;
        ssao.samples = 16;
        ssao.maxZ = 100;
    } catch (e) { console.log("SSAO not supported"); }

    // Image Processing: Slight contrast boost to make textures pop
    scene.imageProcessingConfiguration.contrast = 1.2;
    scene.imageProcessingConfiguration.exposure = 1.0;

    const ground = BABYLON.MeshBuilder.CreateGround("ground", { width: 100, height: 100 }, scene);
    ground.position.y = -0.01;
    ground.receiveShadows = true;

    const gridMat = new BABYLON.StandardMaterial("grid", scene);
    gridMat.diffuseColor = new BABYLON.Color3(0.2, 0.2, 0.2);
    gridMat.wireframe = true;
    ground.material = gridMat;

    scene.registerBeforeRender(() => {
        if (autoRotate) {
            camera.alpha += 0.005;
        }
    });

    engine.runRenderLoop(() => scene.render());
    window.addEventListener('resize', () => engine.resize());
}

function resolveLeafColor(blockId, biome) {
    if (FIXED_BLOCK_TINTS[blockId]) {
        return BABYLON.Color3.FromHexString(FIXED_BLOCK_TINTS[blockId]);
    }
    const hex = BIOME_COLORS[biome] || BIOME_COLORS['plains'];
    return BABYLON.Color3.FromHexString(hex);
}

// Helper function to recursively extract block types from complex feature JSON
function extractBlockTypesFromFeature(json) {
    const blocks = { trunks: new Set(), foliage: new Set() };

    function scan(obj) {
        if (!obj || typeof obj !== 'object') return;

        // Check for trunk_provider
        if (obj.trunk_provider && obj.trunk_provider.state && obj.trunk_provider.state.Name) {
            blocks.trunks.add(obj.trunk_provider.state.Name);
        }

        // Check for foliage_provider
        if (obj.foliage_provider) {
            if (obj.foliage_provider.state && obj.foliage_provider.state.Name) {
                blocks.foliage.add(obj.foliage_provider.state.Name);
            }
            // Handle weighted_state_provider
            if (obj.foliage_provider.entries && Array.isArray(obj.foliage_provider.entries)) {
                obj.foliage_provider.entries.forEach(entry => {
                    if (entry.data && entry.data.Name) {
                        blocks.foliage.add(entry.data.Name);
                    }
                });
            }
        }

        // Recursively scan all properties
        for (const key in obj) {
            if (obj.hasOwnProperty(key)) {
                scan(obj[key]);
            }
        }
    }

    scan(json);
    return blocks;
}

async function updateMaterials() {
    Object.values(masterMeshes).forEach(m => {
        if (m.material) m.material.dispose();
        m.dispose();
    });
    masterMeshes = {};

    const pack = document.getElementById('resource_pack').value;
    const path = `/textures/${pack}/`;

    // Extract block names from the current tree JSON or form
    let trunkRaw = "minecraft:oak_log";
    let foliageRaw = "minecraft:oak_leaves";

    if (window.currentTreeJson) {
        // Use helper to extract from complex JSON
        const extracted = extractBlockTypesFromFeature(window.currentTreeJson);
        if (extracted.trunks.size > 0) {
            trunkRaw = Array.from(extracted.trunks)[0]; // Use first trunk type found
        }
        if (extracted.foliage.size > 0) {
            foliageRaw = Array.from(extracted.foliage)[0]; // Use first foliage type found
        }
    }

    const trunkName = trunkRaw.split(':').pop();
    const foliageName = foliageRaw.split(':').pop();

    // Read biome from selector
    const biomeSelect = document.getElementById('biome_select');
    const selectedBiome = biomeSelect ? biomeSelect.value : 'plains';
    const leafColor = resolveLeafColor(foliageRaw, selectedBiome);

    const loadTex = (name) => {
        return new BABYLON.Texture(path + name, scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    }

    // --- 1. LOGS ---
    const matSide = new BABYLON.StandardMaterial("logSide", scene);
    matSide.diffuseTexture = loadTex(trunkName + ".png");
    matSide.specularColor = BABYLON.Color3.Black();

    const matTop = new BABYLON.StandardMaterial("logTop", scene);
    matTop.diffuseTexture = loadTex(trunkName + "_top.png");
    matTop.specularColor = BABYLON.Color3.Black();

    const logSideRotatedTex = loadTex(trunkName + ".png");
    logSideRotatedTex.wAng = Math.PI / 2;
    logSideRotatedTex.uRotationCenter = 0.5;
    logSideRotatedTex.vRotationCenter = 0.5;

    const matSideRotated = new BABYLON.StandardMaterial("logSideRot", scene);
    matSideRotated.diffuseTexture = logSideRotatedTex;
    matSideRotated.specularColor = BABYLON.Color3.Black();

    const multiLog = new BABYLON.MultiMaterial("multiLog", scene);
    multiLog.subMaterials.push(matSide);
    multiLog.subMaterials.push(matTop);
    multiLog.subMaterials.push(matSideRotated);

    const faceUV = new Array(6).fill(new BABYLON.Vector4(0, 0, 1, 1));
    const logMesh = BABYLON.MeshBuilder.CreateBox("master_log", { size: 1, faceUV: faceUV }, scene);
    logMesh.material = multiLog;
    logMesh.subMeshes = [];
    new BABYLON.SubMesh(0, 0, 24, 0, 12, logMesh);
    new BABYLON.SubMesh(2, 0, 24, 12, 12, logMesh);
    new BABYLON.SubMesh(1, 0, 24, 24, 12, logMesh);

    logMesh.isVisible = false;
    masterMeshes['log'] = logMesh;

    // --- 2. LEAVES ---
    const leafMat = new BABYLON.StandardMaterial("leafMat", scene);
    const leafTex = loadTex(foliageName + ".png");
    leafTex.hasAlpha = true;
    leafMat.diffuseTexture = leafTex;
    leafMat.diffuseColor = leafColor;
    leafMat.specularColor = BABYLON.Color3.Black();
    leafMat.transparencyMode = BABYLON.Material.MATERIAL_ALPHATEST;
    leafMat.alphaCutoff = 0.5;
    leafMat.backFaceCulling = true;

    const leafMesh = BABYLON.MeshBuilder.CreateBox("master_leaves", { size: 1 }, scene);
    leafMesh.material = leafMat;
    leafMesh.isVisible = false;
    masterMeshes['leaves'] = leafMesh;

    // --- 3. DIRT ---
    const dirtMat = new BABYLON.StandardMaterial("dirtMat", scene);
    dirtMat.diffuseTexture = loadTex("dirt.png");
    dirtMat.specularColor = BABYLON.Color3.Black();

    const dirtMesh = BABYLON.MeshBuilder.CreateBox("master_dirt", { size: 1 }, scene);
    dirtMesh.material = dirtMat;
    dirtMesh.isVisible = false;
    masterMeshes['dirt'] = dirtMesh;

    // --- 4. BEEHIVE/BEE NEST ---
    const beehiveMat = new BABYLON.StandardMaterial("beehiveMat", scene);
    beehiveMat.diffuseTexture = loadTex("bee_nest_front.png"); // Use bee_nest texture for both
    beehiveMat.specularColor = BABYLON.Color3.Black();

    const beehiveMesh = BABYLON.MeshBuilder.CreateBox("master_beehive", { size: 1 }, scene);
    beehiveMesh.material = beehiveMat;
    beehiveMesh.isVisible = false;
    masterMeshes['beehive'] = beehiveMesh;
}

async function generateTree() {
    const btn = document.getElementById('btn_generate');
    const status = document.getElementById('status');
    if (btn) btn.disabled = true;
    if (status) status.textContent = "Generating...";

    // Get the raw Minecraft JSON config
    let featureJson = null;

    if (window.currentTreeJson) {
        // Use the full loaded JSON (preserves wrappers like random_patch)
        featureJson = window.currentTreeJson;

        // If we have form data, we might need to update the internal config
        // For now, we assume the form updates window.currentTreeJson.config directly if it's linked
        // (This might need refinement for complex wrappers if the form only edits a sub-part)
    } else {
        // Extract from form and wrap in minecraft:tree
        const container = document.getElementById('dynamic-form-container');
        if (window.treeBrowser && window.treeBrowser.schemaFormBuilder && container) {
            const config = window.treeBrowser.schemaFormBuilder.extractValues(container);
            featureJson = {
                type: "minecraft:tree",
                config: config
            };
        }
    }

    if (!featureJson) {
        console.error('No tree config available for generation');
        if (status) status.textContent = 'Error: No tree data';
        if (btn) btn.disabled = false;
        return;
    }

    await updateMaterials();

    try {
        const response = await fetch('/api/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(featureJson)
        });
        if (!response.ok) throw new Error("API Error: " + await response.text());
        const blocks = await response.json();
        renderScene(blocks);
        if (status) status.textContent = `Generated ${blocks.length} blocks.`;
    } catch (error) {
        console.error(error);
        if (status) status.textContent = 'Error: ' + error.message;
    } finally {
        if (btn) btn.disabled = false;
    }
}

function renderScene(blocks) {
    // Clear existing meshes
    Object.values(masterMeshes).forEach(mesh => {
        if (mesh.material) {
            if (mesh.material.subMaterials) {
                mesh.material.subMaterials.forEach(m => m.dispose());
            }
            mesh.material.dispose();
        }
        mesh.dispose();
    });
    masterMeshes = {};

    if (blocks.length === 0) return;

    // Group blocks by their exact block type
    const blocksByType = {};
    blocks.forEach(b => {
        const blockName = b.blockState.Name;
        if (!blocksByType[blockName]) {
            blocksByType[blockName] = [];
        }
        blocksByType[blockName].push(b);
    });

    const pack = document.getElementById('resource_pack').value;
    const path = `/textures/${pack}/`;
    const biomeSelect = document.getElementById('biome_select');
    const selectedBiome = biomeSelect ? biomeSelect.value : 'plains';

    // Create materials and render each block type
    Object.entries(blocksByType).forEach(([blockName, blockList]) => {
        const blockId = blockName.split(':').pop();
        const isLog = blockName.includes('log') || blockName.includes('wood');
        const isLeaf = blockName.includes('leaves');

        if (isLog) {
            renderLogs(blockList, blockId, path);
        } else if (isLeaf) {
            renderLeaves(blockList, blockName, blockId, path, selectedBiome);
        } else {
            renderGenericBlocks(blockList, blockName, blockId, path);
        }
    });
}

function renderLogs(blocks, blockId, texturePath) {
    const matSide = new BABYLON.StandardMaterial(`${blockId}_side`, scene);
    matSide.diffuseTexture = new BABYLON.Texture(texturePath + blockId + ".png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    matSide.specularColor = BABYLON.Color3.Black();

    const matTop = new BABYLON.StandardMaterial(`${blockId}_top`, scene);
    matTop.diffuseTexture = new BABYLON.Texture(texturePath + blockId + "_top.png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    matTop.specularColor = BABYLON.Color3.Black();

    const logSideRotatedTex = new BABYLON.Texture(texturePath + blockId + ".png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    logSideRotatedTex.wAng = Math.PI / 2;
    logSideRotatedTex.uRotationCenter = 0.5;
    logSideRotatedTex.vRotationCenter = 0.5;

    const matSideRotated = new BABYLON.StandardMaterial(`${blockId}_side_rot`, scene);
    matSideRotated.diffuseTexture = logSideRotatedTex;
    matSideRotated.specularColor = BABYLON.Color3.Black();

    const multiLog = new BABYLON.MultiMaterial(`multi_${blockId}`, scene);
    multiLog.subMaterials.push(matSide);
    multiLog.subMaterials.push(matTop);
    multiLog.subMaterials.push(matSideRotated);

    const faceUV = new Array(6).fill(new BABYLON.Vector4(0, 0, 1, 1));
    const logMesh = BABYLON.MeshBuilder.CreateBox(`master_${blockId}`, { size: 1, faceUV: faceUV }, scene);
    logMesh.material = multiLog;
    logMesh.subMeshes = [];
    new BABYLON.SubMesh(0, 0, 24, 0, 12, logMesh);
    new BABYLON.SubMesh(2, 0, 24, 12, 12, logMesh);
    new BABYLON.SubMesh(1, 0, 24, 24, 12, logMesh);
    logMesh.isVisible = false;
    masterMeshes[blockId] = logMesh;

    const matrices = [];
    blocks.forEach(b => {
        let rotationMatrix = BABYLON.Matrix.Identity();
        if (b.blockState.Properties && b.blockState.Properties.axis) {
            const axis = b.blockState.Properties.axis;
            if (axis === 'x') {
                rotationMatrix = BABYLON.Matrix.RotationZ(Math.PI / 2);
            } else if (axis === 'z') {
                rotationMatrix = BABYLON.Matrix.RotationX(Math.PI / 2);
            }
        }
        let matrix = rotationMatrix.multiply(BABYLON.Matrix.Translation(b.x, b.y + 0.5, b.z));
        matrices.push(matrix);
    });

    if (matrices.length > 0) {
        const buffer = new Float32Array(matrices.length * 16);
        matrices.forEach((m, i) => m.copyToArray(buffer, i * 16));
        logMesh.thinInstanceSetBuffer("matrix", buffer, 16, true);
        logMesh.isVisible = true;
        shadowGenerator.addShadowCaster(logMesh);
    }
}

function renderLeaves(blocks, fullBlockName, blockId, texturePath, biome) {
    const leafMat = new BABYLON.StandardMaterial(`${blockId}_mat`, scene);
    const leafTex = new BABYLON.Texture(texturePath + blockId + ".png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    leafTex.hasAlpha = true;
    leafMat.diffuseTexture = leafTex;
    leafMat.diffuseColor = resolveLeafColor(fullBlockName, biome);
    leafMat.specularColor = BABYLON.Color3.Black();
    leafMat.transparencyMode = BABYLON.Material.MATERIAL_ALPHATEST;
    leafMat.alphaCutoff = 0.5;
    leafMat.backFaceCulling = true;

    const leafMesh = BABYLON.MeshBuilder.CreateBox(`master_${blockId}`, { size: 1 }, scene);
    leafMesh.material = leafMat;
    leafMesh.isVisible = false;
    masterMeshes[blockId] = leafMesh;

    const matrices = [];
    blocks.forEach(b => {
        let matrix = BABYLON.Matrix.Translation(b.x, b.y + 0.5, b.z);
        matrices.push(matrix);
    });

    if (matrices.length > 0) {
        const buffer = new Float32Array(matrices.length * 16);
        matrices.forEach((m, i) => m.copyToArray(buffer, i * 16));
        leafMesh.thinInstanceSetBuffer("matrix", buffer, 16, true);
        leafMesh.isVisible = true;
        shadowGenerator.addShadowCaster(leafMesh);
    }
}

function renderGenericBlocks(blocks, fullBlockName, blockId, texturePath) {
    const mat = new BABYLON.StandardMaterial(`${blockId}_mat`, scene);

    let textureName = blockId + ".png";
    if (blockId === 'bee_nest' || blockId === 'beehive') {
        textureName = "bee_nest_front.png";
    }

    mat.diffuseTexture = new BABYLON.Texture(texturePath + textureName, scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    mat.specularColor = BABYLON.Color3.Black();

    const mesh = BABYLON.MeshBuilder.CreateBox(`master_${blockId}`, { size: 1 }, scene);
    mesh.material = mat;
    mesh.isVisible = false;
    masterMeshes[blockId] = mesh;

    const matrices = [];
    blocks.forEach(b => {
        let matrix = BABYLON.Matrix.Translation(b.x, b.y + 0.5, b.z);
        matrices.push(matrix);
    });

    if (matrices.length > 0) {
        const buffer = new Float32Array(matrices.length * 16);
        matrices.forEach((m, i) => m.copyToArray(buffer, i * 16));
        mesh.thinInstanceSetBuffer("matrix", buffer, 16, true);
        mesh.isVisible = true;
        shadowGenerator.addShadowCaster(mesh);
    }
}

function setupUI() {
    const debouncedGenerate = debounce(generateTree, 400);

    // We no longer have specific IDs to bind to.
    // Instead, we can listen for changes on the form container.
    const formContainer = document.getElementById('dynamic-form-container');
    if (formContainer) {
        formContainer.addEventListener('input', (e) => {
            // When any input changes, we want to update the wrapper config and regenerate
            // But we don't want to regenerate on every keystroke for text inputs, so we debounce.

            // Sync the wrapper config immediately? Or just let generateTree handle extraction?
            // Let's let generateTree handle extraction for preview.

            // If it's a block change, we might want to update materials immediately?
            // But updateMaterials is async and called by generateTree.

            debouncedGenerate();
        });

        formContainer.addEventListener('change', (e) => {
            // For select/checkbox changes, trigger immediately
            debouncedGenerate();
        });
    }

    // JSON editor changes
    const jsonEditor = document.getElementById('json-editor');
    if (jsonEditor) {
        jsonEditor.addEventListener('input', () => {
            try {
                const json = jsonEditor.value;
                const parsed = JSON.parse(json);
                window.currentTreeJson = parsed;
                debouncedGenerate();
            } catch (e) {
                // Invalid JSON, don't update
            }
        });
    }

    // Biome selector
    document.getElementById('biome_select')?.addEventListener('change', () => {
        debouncedGenerate();
    });

    // Helper to trigger rotation
    document.getElementById('btn_rotate')?.addEventListener('click', toggleRotation);
    document.getElementById('btn_reset_camera')?.addEventListener('click', resetCamera);
}

function debounce(func, wait) {
    let timeout;
    return function executedFunction(...args) {
        const later = () => {
            clearTimeout(timeout);
            func(...args);
        };
        clearTimeout(timeout);
        timeout = setTimeout(later, wait);
    };
}

function toggleRotation() {
    autoRotate = !autoRotate;
    document.getElementById('btn_rotate').classList.toggle('active', autoRotate);
}

function resetCamera() {
    autoRotate = false;
    document.getElementById('btn_rotate').classList.remove('active');
    camera.setTarget(new BABYLON.Vector3(0, 5, 0));
    camera.alpha = -Math.PI / 2;
    camera.beta = Math.PI / 3;
    camera.radius = 15;
}
