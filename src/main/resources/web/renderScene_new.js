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
        const blockId = blockName.split(':').pop(); // e.g., "spruce_leaves"
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
