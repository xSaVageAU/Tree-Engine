// Leaf block rendering service

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