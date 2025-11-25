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

    if (window.currentTreeJson && window.currentTreeJson.config) {
        const config = window.currentTreeJson.config;
        if (config.trunk_provider && config.trunk_provider.state) {
            trunkRaw = config.trunk_provider.state.Name || trunkRaw;
        }
        if (config.foliage_provider && config.foliage_provider.state) {
            foliageRaw = config.foliage_provider.state.Name || foliageRaw;
        }
    }

    const trunkName = trunkRaw.split(':').pop();
    const foliageName = foliageRaw.split(':').pop();
    // Biome select might still be there if I didn't remove it from index.html (I did remove it)
    // Wait, I removed the biome select from index.html in the rewrite.
    // So we should probably default to 'plains' or add it back to metadata if needed.
    // For now, let's default to plains.
    const selectedBiome = 'plains';
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
    let config = null;

    if (window.currentTreeJson && window.currentTreeJson.config) {
        config = window.currentTreeJson.config;
    } else {
        // Extract from form
        const container = document.getElementById('dynamic-form-container');
        if (window.treeBrowser && window.treeBrowser.schemaFormBuilder && container) {
            config = window.treeBrowser.schemaFormBuilder.extractValues(container);
        }
    }

    if (!config) {
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
            body: JSON.stringify(config)
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
    const matrices = { 'log': [], 'leaves': [], 'dirt': [], 'beehive': [] };
    blocks.forEach(b => {
        let type = 'leaves'; // default
        if (b.block.includes('log') || b.block.includes('wood')) type = 'log';
        else if (b.block.includes('dirt')) type = 'dirt';
        else if (b.block.includes('beehive') || b.block.includes('bee_nest')) type = 'beehive';
        else if (!b.block.includes('leaves')) type = 'leaves'; // if not leaves, but wait

        const matrix = BABYLON.Matrix.Translation(b.x, b.y + 0.5, b.z);
        matrices[type].push(matrix);
    });

    const types = ['log', 'leaves', 'dirt', 'beehive'];
    types.forEach(type => {
        if (masterMeshes[type]) {
            masterMeshes[type].thinInstanceSetBuffer("matrix", null);
            if (matrices[type].length > 0) {
                const buffer = new Float32Array(matrices[type].length * 16);
                matrices[type].forEach((m, i) => m.copyToArray(buffer, i * 16));
                masterMeshes[type].thinInstanceSetBuffer("matrix", buffer, 16, true);
                masterMeshes[type].isVisible = true;
                shadowGenerator.addShadowCaster(masterMeshes[type]);
            } else masterMeshes[type].isVisible = false;
        }
    });
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

    // Biome select was removed, so no listener needed.

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
