// Renderer for cross-model blocks (flowers, saplings, grass, etc.)

function renderCrossBlock(blocks, blockId, texturePath) {
    const mat = new BABYLON.StandardMaterial(`${blockId}_cross_mat`, scene);
    mat.diffuseTexture = new BABYLON.Texture(texturePath + blockId + ".png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    mat.emissiveTexture = mat.diffuseTexture; // Use emissive for uniform brightness
    mat.diffuseColor = BABYLON.Color3.Black(); // Disable diffuse lighting
    mat.diffuseTexture.hasAlpha = true;
    mat.diffuseTexture.vScale = -1; // Flip texture vertically to correct upside-down appearance
    mat.useAlphaFromDiffuseTexture = true;
    mat.backFaceCulling = false;
    mat.twoSidedLighting = true;
    mat.specularColor = BABYLON.Color3.Black();
    mat.transparencyMode = BABYLON.Material.MATERIAL_ALPHATEST;
    mat.alphaCutOff = 0.5;

    const plane1 = BABYLON.MeshBuilder.CreatePlane(`master_${blockId}_1`, { size: 1 }, scene);
    plane1.material = mat;
    plane1.isVisible = false;

    const plane2 = BABYLON.MeshBuilder.CreatePlane(`master_${blockId}_2`, { size: 1 }, scene);
    plane2.material = mat;
    plane2.isVisible = false;

    masterMeshes[`${blockId}_1`] = plane1;
    masterMeshes[`${blockId}_2`] = plane2;

    const matrices1 = [];
    const matrices2 = [];
    blocks.forEach(b => {
        const translation = BABYLON.Matrix.Translation(b.x, b.y + 0.5, b.z);
        const rotY1 = BABYLON.Matrix.RotationY(Math.PI / 4);
        const rotY2 = BABYLON.Matrix.RotationY(-Math.PI / 4);
        matrices1.push(rotY1.multiply(translation));
        matrices2.push(rotY2.multiply(translation));
    });

    if (matrices1.length > 0) {
        const buffer1 = new Float32Array(matrices1.length * 16);
        matrices1.forEach((m, i) => m.copyToArray(buffer1, i * 16));
        plane1.thinInstanceSetBuffer("matrix", buffer1, 16, true);
        plane1.isVisible = true;
    }
    if (matrices2.length > 0) {
        const buffer2 = new Float32Array(matrices2.length * 16);
        matrices2.forEach((m, i) => m.copyToArray(buffer2, i * 16));
        plane2.thinInstanceSetBuffer("matrix", buffer2, 16, true);
        plane2.isVisible = true;
    }
}
