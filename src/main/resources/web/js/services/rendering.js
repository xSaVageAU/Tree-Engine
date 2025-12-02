// Rendering service for Babylon.js 3D scene

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

        // Check for complex model first
        if (ComplexBlockRenderer.MODEL_TYPES[blockId]) {
            ComplexBlockRenderer.render(blockList, blockName, blockId, path, selectedBiome);
        } else if (isLog) {
            renderLogs(blockList, blockId, path);
        } else if (isLeaf) {
            renderLeaves(blockList, blockName, blockId, path, selectedBiome);
        } else if (blockId === 'podzol' || blockId === 'mycelium' || blockId === 'grass_block') {
            renderGroundBlocks(blockList, blockId, path);
        } else {
            renderGenericBlocks(blockList, blockName, blockId, path);
        }
    });
}

function clearScene() {
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
}


