// UI component for setting up event listeners and interactions

function setupUI() {
    const debouncedGenerate = debounce(generateTree, 400);

    // We no longer have specific IDs to bind to.
    // Instead, we can listen for changes on the form container.
    const formContainer = document.getElementById('dynamic-form-container');
    if (formContainer) {
        formContainer.addEventListener('input', (e) => {
            // When any input changes, we want to update the wrapper config and regenerate
            // But we don't want to regenerate on every keystroke for text inputs, so we debounce.

            // Sync the wrapper config immediately? Or just let generateTree handle extraction?
            // Let's let generateTree handle extraction for preview.

            // If it's a block change, we might want to update materials immediately?
            // But updateMaterials is async and called by generateTree.

            debouncedGenerate();
        });

        formContainer.addEventListener('change', (e) => {
            // For select/checkbox changes, trigger immediately
            debouncedGenerate();
        });
    }

    // JSON editor changes
    const jsonEditor = document.getElementById('json-editor');
    if (jsonEditor) {
        jsonEditor.addEventListener('input', () => {
            try {
                const json = jsonEditor.value;
                const parsed = JSON.parse(json);
                window.currentTreeJson = parsed;
                debouncedGenerate();
            } catch (e) {
                // Invalid JSON, don't update
            }
        });
    }

    // Biome selector
    document.getElementById('biome_select')?.addEventListener('change', () => {
        debouncedGenerate();
    });

    // Resource pack selector
    const resourcePackSelect = document.getElementById('resource_pack');
    if (resourcePackSelect) {
        resourcePackSelect.addEventListener('change', () => {
            debouncedGenerate();
        });
        loadTexturePacks();
    }

    // Helper to trigger rotation
    document.getElementById('btn_rotate')?.addEventListener('click', toggleRotation);
    document.getElementById('btn_reset_camera')?.addEventListener('click', resetCamera);

    // Background color picker
    const bgColorPicker = document.getElementById('bg_color_picker');
    if (bgColorPicker) {
        bgColorPicker.addEventListener('input', (e) => {
            if (scene) {
                const color = BABYLON.Color3.FromHexString(e.target.value);
                scene.clearColor = color;
                scene.fogColor = color;
            }
        });
    }


    // Auth token UI
    setupAuthTokenUI();
}

async function loadTexturePacks() {
    const select = document.getElementById('resource_pack');
    if (!select) return;

    const packs = await fetchTexturePacks();

    // Clear existing options (except maybe a loading one if we added it)
    select.innerHTML = '';

    if (packs.length === 0) {
        const option = document.createElement('option');
        option.value = 'default';
        option.textContent = 'Default';
        select.appendChild(option);
        return;
    }

    packs.forEach(pack => {
        const option = document.createElement('option');
        option.value = pack;
        option.textContent = pack;
        select.appendChild(option);
    });

    // Try to select 'minecraft-assets-1.21.10' if it exists, otherwise the first one
    // Or maybe we should store the last selected one in localStorage?
    // For now, let's just default to the first one or a specific one if found.
    const preferred = 'minecraft-assets-1.21.10';
    if (packs.includes(preferred)) {
        select.value = preferred;
    } else if (packs.length > 0) {
        select.value = packs[0];
    }
}

function setupAuthTokenUI() {
    const tokenInput = document.getElementById('auth-token-input');
    const saveButton = document.getElementById('btn-save-token');
    const statusDiv = document.getElementById('auth-status');

    if (!tokenInput || !saveButton || !statusDiv) return;

    // Load existing token on page load
    const existingToken = getAuthToken();
    if (existingToken) {
        statusDiv.textContent = '✓ Token saved';
        statusDiv.style.color = '#4a9a4a';
    } else {
        statusDiv.textContent = 'No token set - check console for token';
        statusDiv.style.color = '#858585';
    }

    // Save token button
    saveButton.addEventListener('click', () => {
        const token = tokenInput.value.trim();
        if (token) {
            setAuthToken(token);
            statusDiv.textContent = '✓ Token saved';
            statusDiv.style.color = '#4a9a4a';
            tokenInput.value = ''; // Clear input for security
        } else {
            statusDiv.textContent = 'Please enter a token';
            statusDiv.style.color = '#ff6b6b';
        }
    });

    // Allow Enter key to save
    tokenInput.addEventListener('keypress', (e) => {
        if (e.key === 'Enter') {
            saveButton.click();
        }
    });
}