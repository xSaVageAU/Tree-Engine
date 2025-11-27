// Generic block rendering service

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