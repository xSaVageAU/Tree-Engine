// Renderer for cocoa beans

function renderCocoaBlock(blocks, blockId, texturePath) {
    const sizes = [
        { w: 4 / 16, h: 5 / 16, d: 4 / 16 },
        { w: 6 / 16, h: 7 / 16, d: 6 / 16 },
        { w: 8 / 16, h: 9 / 16, d: 8 / 16 }
    ];

    const masters = [];
    for (let age = 0; age < 3; age++) {
        const size = sizes[age];
        const mat = new BABYLON.StandardMaterial(`cocoa_age${age}_mat`, scene);
        mat.diffuseTexture = new BABYLON.Texture(texturePath + `cocoa_stage${age}.png`, scene, null, null, BABYLON.Texture.NEAREST_SAMPLINGMODE);
        mat.specularColor = BABYLON.Color3.Black();
        const mesh = BABYLON.MeshBuilder.CreateBox(`master_cocoa_${age}`, { width: size.w, height: size.h, depth: size.d }, scene);
        mesh.material = mat;
        mesh.isVisible = false;
        masters[age] = mesh;
        masterMeshes[`cocoa_${age}`] = mesh;
    }

    const matrices = [[], [], []];
    blocks.forEach(b => {
        const props = b.blockState.Properties || {};
        const age = parseInt(props.age || '0');
        const facing = props.facing || 'north';
        const internalOffset = 0.5 - sizes[age].d / 2;
        let position = new BABYLON.Vector3(b.x, b.y + 0.5, b.z);
        let rotationY = 0;
        let offset = new BABYLON.Vector3(0, 0, 0);

        if (facing === 'north') {
            offset.z = internalOffset;
            rotationY = 0;
        } else if (facing === 'south') {
            offset.z = -internalOffset;
            rotationY = Math.PI;
        } else if (facing === 'west') {
            offset.x = internalOffset;
            rotationY = -Math.PI / 2;
        } else if (facing === 'east') {
            offset.x = -internalOffset;
            rotationY = Math.PI / 2;
        }

        let matrix = BABYLON.Matrix.RotationY(rotationY)
            .multiply(BABYLON.Matrix.Translation(position.x + offset.x, position.y, position.z + offset.z));
        matrices[age].push(matrix);
    });

    for (let age = 0; age < 3; age++) {
        if (matrices[age].length > 0) {
            const buffer = new Float32Array(matrices[age].length * 16);
            matrices[age].forEach((m, i) => m.copyToArray(buffer, i * 16));
            masters[age].thinInstanceSetBuffer("matrix", buffer, 16, true);
            masters[age].isVisible = true;
        }
    }
}
