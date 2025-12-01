// Renderer for cross-model blocks (flowers, saplings, grass, etc.)

function renderCrossBlock(blocks, blockId, texturePath) {
    const mat = new BABYLON.StandardMaterial(`${blockId}_cross_mat`, scene);
    mat.diffuseTexture = new BABYLON.Texture(texturePath + blockId + ".png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    mat.diffuseTexture.hasAlpha = true;
    mat.useAlphaFromDiffuseTexture = true;
    mat.backFaceCulling = false;
    mat.specularColor = BABYLON.Color3.Black();

    const plane1 = BABYLON.MeshBuilder.CreatePlane(`master_${blockId}_1`, { size: 1 }, scene);
    plane1.rotation.y = Math.PI / 4;
    plane1.material = mat;
    plane1.isVisible = false;

    const plane2 = BABYLON.MeshBuilder.CreatePlane(`master_${blockId}_2`, { size: 1 }, scene);
    plane2.rotation.y = -Math.PI / 4;
    plane2.material = mat;
    plane2.isVisible = false;

    masterMeshes[`${blockId}_1`] = plane1;
    masterMeshes[`${blockId}_2`] = plane2;

    const matrices = [];
    blocks.forEach(b => {
        let matrix = BABYLON.Matrix.Translation(b.x, b.y + 0.5, b.z);
        matrices.push(matrix);
    });

    if (matrices.length > 0) {
        const buffer = new Float32Array(matrices.length * 16);
        matrices.forEach((m, i) => m.copyToArray(buffer, i * 16));
        plane1.thinInstanceSetBuffer("matrix", buffer, 16, true);
        plane2.thinInstanceSetBuffer("matrix", buffer, 16, true);
        plane1.isVisible = true;
        plane2.isVisible = true;
    }
}
