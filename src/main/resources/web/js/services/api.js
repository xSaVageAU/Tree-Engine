// API service for tree generation and data extraction

// Helper function to recursively extract block types from complex feature JSON
function extractBlockTypesFromFeature(json) {
    const blocks = { trunks: new Set(), foliage: new Set() };

    function scan(obj) {
        if (!obj || typeof obj !== 'object') return;

        // Check for trunk_provider
        if (obj.trunk_provider && obj.trunk_provider.state && obj.trunk_provider.state.Name) {
            blocks.trunks.add(obj.trunk_provider.state.Name);
        }

        // Check for foliage_provider
        if (obj.foliage_provider) {
            if (obj.foliage_provider.state && obj.foliage_provider.state.Name) {
                blocks.foliage.add(obj.foliage_provider.state.Name);
            }
            // Handle weighted_state_provider
            if (obj.foliage_provider.entries && Array.isArray(obj.foliage_provider.entries)) {
                obj.foliage_provider.entries.forEach(entry => {
                    if (entry.data && entry.data.Name) {
                        blocks.foliage.add(entry.data.Name);
                    }
                });
            }
        }

        // Recursively scan all properties
        for (const key in obj) {
            if (obj.hasOwnProperty(key)) {
                scan(obj[key]);
            }
        }
    }

    scan(json);
    return blocks;
}

async function generateTree() {
    const btn = document.getElementById('btn_generate');
    const status = document.getElementById('status');
    if (btn) btn.disabled = true;
    if (status) status.textContent = "Generating...";

    // Get the raw Minecraft JSON config
    let featureJson = null;

    if (window.currentTreeJson) {
        // Use the full loaded JSON (preserves wrappers like random_patch)
        featureJson = window.currentTreeJson;
    } else {
        // Extract from form and wrap in minecraft:tree
        const container = document.getElementById('dynamic-form-container');
        if (window.treeBrowser && window.treeBrowser.schemaFormBuilder && container) {
            const config = window.treeBrowser.schemaFormBuilder.extractValues(container);
            featureJson = {
                type: "minecraft:tree",
                config: config
            };
        }
    }

    if (!featureJson) {
        console.error('No tree config available for generation');
        if (status) status.textContent = 'Error: No tree data';
        if (btn) btn.disabled = false;
        return;
    }

    try {
        const response = await fetch('/api/generate', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(featureJson)
        });
        if (!response.ok) throw new Error("API Error: " + await response.text());
        let blocks = await response.json();

        // Filter out air blocks
        blocks = blocks.filter(b => b.blockState.Name !== "minecraft:air");

        renderScene(blocks);
        if (status) status.textContent = `Generated ${blocks.length} blocks.`;
    } catch (error) {
        console.error(error);
        if (status) status.textContent = 'Error: ' + error.message;
    } finally {
        if (btn) btn.disabled = false;
    }
}

async function fetchTexturePacks() {
    try {
        const response = await fetch('/api/texture-packs');
        if (!response.ok) throw new Error("Failed to fetch texture packs");
        return await response.json();
    } catch (error) {
        console.error("Error fetching texture packs:", error);
        return ["default"];
    }
}