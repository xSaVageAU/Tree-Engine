// Ground block rendering service (Podzol, Mycelium, Grass Block)

function renderGroundBlocks(blocks, blockId, texturePath) {
    const matSide = new BABYLON.StandardMaterial(`${blockId}_side`, scene);
    matSide.diffuseTexture = new BABYLON.Texture(texturePath + blockId + "_side.png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    matSide.specularColor = BABYLON.Color3.Black();

    const matTop = new BABYLON.StandardMaterial(`${blockId}_top`, scene);
    matTop.diffuseTexture = new BABYLON.Texture(texturePath + blockId + "_top.png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    matTop.specularColor = BABYLON.Color3.Black();

    // Bottom is usually dirt
    const matBottom = new BABYLON.StandardMaterial(`${blockId}_bottom`, scene);
    matBottom.diffuseTexture = new BABYLON.Texture(texturePath + "dirt.png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    matBottom.specularColor = BABYLON.Color3.Black();

    const multiMat = new BABYLON.MultiMaterial(`multi_${blockId}`, scene);
    multiMat.subMaterials.push(matSide);   // 0: Side
    multiMat.subMaterials.push(matTop);    // 1: Top
    multiMat.subMaterials.push(matBottom); // 2: Bottom

    // Create box
    // Babylon default face order: 0:Z+, 1:Z-, 2:X+, 3:X-, 4:Y+, 5:Y-
    // We want:
    // Sides (0, 1, 2, 3) -> Material 0
    // Top (4) -> Material 1
    // Bottom (5) -> Material 2

    const mesh = BABYLON.MeshBuilder.CreateBox(`master_${blockId}`, { size: 1 }, scene);
    mesh.material = multiMat;

    // SubMeshes
    mesh.subMeshes = [];

    // Sides: Faces 0, 1, 2, 3 (4 faces * 6 indices = 24 indices)
    // Indices 0 to 24
    new BABYLON.SubMesh(0, 0, 24, 0, 24, mesh);

    // Top: Face 4 (1 face * 6 indices = 6 indices)
    // Indices 24 to 30
    new BABYLON.SubMesh(1, 0, 24, 24, 6, mesh);

    // Bottom: Face 5 (1 face * 6 indices = 6 indices)
    // Indices 30 to 36
    new BABYLON.SubMesh(2, 0, 24, 30, 6, mesh);

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
