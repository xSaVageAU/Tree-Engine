// Renderer for wall-mounted blocks (vines, ladders, glow lichen, etc.)

function renderWallBlock(blocks, blockId, texturePath, biome) {
    const mat = new BABYLON.StandardMaterial(`${blockId}_wall_mat`, scene);
    mat.diffuseTexture = new BABYLON.Texture(texturePath + blockId + ".png", scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
    mat.diffuseTexture.hasAlpha = blockId === 'vine' ? true : false;
    mat.useAlphaFromDiffuseTexture = true;
    mat.backFaceCulling = false;
    mat.twoSidedLighting = true;
    mat.specularColor = BABYLON.Color3.Black(); // No specular highlights

    // Apply biome tinting for vines (they use foliage colors like leaves)
    if (blockId === 'vine') {
        mat.diffuseColor = resolveLeafColor('minecraft:vine', biome);
        mat.ambientColor = new BABYLON.Color3(0.25, 0.25, 0.25); // Add ambient brightness to match in-game appearance
    }

    // Use alpha test mode for proper transparency sorting
    mat.transparencyMode = BABYLON.Material.MATERIAL_ALPHATEST;
    mat.alphaCutOff = 0.5;

    // Add slight emission to brighten and reduce shadow darkness
    //mat.emissiveColor = new BABYLON.Color3(0.05, 0.05, 0.05);

    // Use a single master plane, we'll rotate each instance
    const plane = BABYLON.MeshBuilder.CreatePlane(`master_${blockId}`, { size: 1 }, scene);
    plane.material = mat;
    plane.isVisible = false;
    plane.receiveShadows = false;
    masterMeshes[blockId] = plane;

    const matrices = [];
    const offset = 0.49;

    blocks.forEach(b => {
        const props = b.blockState.Properties || {};
        const basePos = new BABYLON.Vector3(b.x, b.y + 0.5, b.z);

        if (props.facing) {
            // Ladder - single directional face
            const facing = props.facing;
            let pos = basePos.clone();
            let rotY = 0;

            if (facing === 'north') { pos.z -= offset; rotY = 0; }
            else if (facing === 'south') { pos.z += offset; rotY = Math.PI; }
            else if (facing === 'west') { pos.x -= offset; rotY = Math.PI / 2; }
            else if (facing === 'east') { pos.x += offset; rotY = -Math.PI / 2; }

            matrices.push(BABYLON.Matrix.RotationY(rotY).multiply(BABYLON.Matrix.Translation(pos.x, pos.y, pos.z)));
        } else {
            // Vines - can have multiple faces
            if (props.north === 'true') {
                const pos = basePos.clone();
                pos.z -= offset;
                matrices.push(BABYLON.Matrix.RotationY(0).multiply(BABYLON.Matrix.Translation(pos.x, pos.y, pos.z)));
            }
            if (props.south === 'true') {
                const pos = basePos.clone();
                pos.z += offset;
                matrices.push(BABYLON.Matrix.RotationY(Math.PI).multiply(BABYLON.Matrix.Translation(pos.x, pos.y, pos.z)));
            }
            if (props.east === 'true') {
                const pos = basePos.clone();
                pos.x += offset;
                matrices.push(BABYLON.Matrix.RotationY(-Math.PI / 2).multiply(BABYLON.Matrix.Translation(pos.x, pos.y, pos.z)));
            }
            if (props.west === 'true') {
                const pos = basePos.clone();
                pos.x -= offset;
                matrices.push(BABYLON.Matrix.RotationY(Math.PI / 2).multiply(BABYLON.Matrix.Translation(pos.x, pos.y, pos.z)));
            }
        }
    });

    if (matrices.length > 0) {
        const buffer = new Float32Array(matrices.length * 16);
        matrices.forEach((m, i) => m.copyToArray(buffer, i * 16));
        plane.thinInstanceSetBuffer("matrix", buffer, 16, true);
        plane.isVisible = true;
    }
}
