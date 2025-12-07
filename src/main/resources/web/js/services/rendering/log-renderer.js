// Log block rendering service

function renderLogs(blocks, blockId, texturePath) {
    // Determine the base texture name
    // For wood blocks (oak_wood, stripped_oak_wood), use the corresponding log texture
    let baseTextureName = blockId;
    if (blockId.endsWith('_wood')) {
        // oak_wood -> oak_log, stripped_oak_wood -> stripped_oak_log
        baseTextureName = blockId.replace('_wood', '_log');
    }

    const matSide = new BABYLON.StandardMaterial(`${blockId}_side`, scene);
    matSide.diffuseTexture = new BABYLON.Texture(texturePath + baseTextureName + ".png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    matSide.specularColor = BABYLON.Color3.Black();
    matSide.useVertexColors = true;

    const matTop = new BABYLON.StandardMaterial(`${blockId}_top`, scene);
    matTop.diffuseTexture = new BABYLON.Texture(texturePath + baseTextureName + "_top.png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    matTop.specularColor = BABYLON.Color3.Black();
    matTop.useVertexColors = true;

    const logSideRotatedTex = new BABYLON.Texture(texturePath + baseTextureName + ".png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    logSideRotatedTex.wAng = Math.PI / 2;
    logSideRotatedTex.uRotationCenter = 0.5;
    logSideRotatedTex.vRotationCenter = 0.5;

    const matSideRotated = new BABYLON.StandardMaterial(`${blockId}_side_rot`, scene);
    matSideRotated.diffuseTexture = logSideRotatedTex;
    matSideRotated.specularColor = BABYLON.Color3.Black();
    matSideRotated.useVertexColors = true;

    const multiLog = new BABYLON.MultiMaterial(`multi_${blockId}`, scene);

    // For wood blocks, use side texture on all faces (no top texture)
    if (blockId.endsWith('_wood')) {
        multiLog.subMaterials.push(matSide);  // All faces use side texture
        multiLog.subMaterials.push(matSide);
        multiLog.subMaterials.push(matSideRotated);
    } else {
        // Regular logs use side + top textures
        multiLog.subMaterials.push(matSide);
        multiLog.subMaterials.push(matTop);
        multiLog.subMaterials.push(matSideRotated);
    }

    // Define face colors for directional shading
    const faceColors = [];
    faceColors[0] = new BABYLON.Color4(0.8, 0.8, 0.8, 1); // Z+ (North/South)
    faceColors[1] = new BABYLON.Color4(0.8, 0.8, 0.8, 1); // Z- (North/South)
    faceColors[2] = new BABYLON.Color4(0.7, 0.7, 0.7, 1); // X+ (East/West)
    faceColors[3] = new BABYLON.Color4(0.7, 0.7, 0.7, 1); // X- (East/West)
    faceColors[4] = new BABYLON.Color4(1, 1, 1, 1);       // Y+ (Top)
    faceColors[5] = new BABYLON.Color4(0.6, 0.6, 0.6, 1); // Y- (Bottom)

    const faceUV = new Array(6).fill(new BABYLON.Vector4(0, 0, 1, 1));
    const logMesh = BABYLON.MeshBuilder.CreateBox(`master_${blockId}`, { size: 1, faceUV: faceUV, faceColors: faceColors }, scene);
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
    }
}