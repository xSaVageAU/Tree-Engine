// API service for tree generation and data extraction

// Authentication token management
function getAuthToken() {
    return localStorage.getItem('tree_engine_auth_token') || '';
}

function setAuthToken(token) {
    localStorage.setItem('tree_engine_auth_token', token);
}

function getAuthHeaders() {
    const headers = { 'Content-Type': 'application/json' };
    const token = getAuthToken();
    if (token) {
        headers['Authorization'] = `Bearer ${token}`;
    }
    return headers;
}

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
    let featureJson = window.currentTreeJson;

    if (!featureJson) {
        console.error('No tree config available for generation');
        if (status) status.textContent = 'Error: No tree data';
        if (btn) btn.disabled = false;
        return;
    }

    try {
        const response = await fetch('/api/generate', {
            method: 'POST',
            headers: getAuthHeaders(),
            body: JSON.stringify(featureJson)
        });

        if (response.status === 401) {
            throw new Error("Authentication required. Please enter your auth token in settings.");
        }

        if (!response.ok) {
            // Try to parse JSON error
            try {
                const errorJson = await response.json();
                if (errorJson.error) {
                    let msg = errorJson.error;
                    if (errorJson.details) {
                        // Create a detailed error object
                        const err = new Error(msg);
                        err.details = errorJson.details;
                        throw err;
                    }
                    throw new Error(msg);
                }
            } catch (e) {
                // If parsing fails or it wasn't JSON, fall back to text
                if (e instanceof Error && e.details) throw e; // Re-throw our detailed error
                throw new Error("API Error: " + await response.text());
            }
        }

        let blocks = await response.json();

        // Filter out air blocks
        blocks = blocks.filter(b => b.blockState.Name !== "minecraft:air");

        renderScene(blocks);
        if (status) status.textContent = `Generated ${blocks.length} blocks.`;
    } catch (error) {
        console.error(error);
        if (status) {
            // Clear previous content
            status.innerHTML = '';

            const msgSpan = document.createElement('span');
            msgSpan.style.color = '#f48771';
            msgSpan.textContent = error.message;
            status.appendChild(msgSpan);

            if (error.details) {
                const link = document.createElement('a');
                link.href = '#';
                link.textContent = ' (Show Details)';
                link.style.color = '#4ec9b0';
                link.style.fontSize = '11px';
                link.style.marginLeft = '5px';

                const detailsDiv = document.createElement('div');
                detailsDiv.style.display = 'none';
                detailsDiv.style.marginTop = '5px';
                detailsDiv.style.fontSize = '11px';
                detailsDiv.style.color = '#ccc';
                detailsDiv.style.background = '#252526';
                detailsDiv.style.padding = '5px';
                detailsDiv.style.borderRadius = '3px';
                detailsDiv.style.whiteSpace = 'pre-wrap';
                detailsDiv.style.wordBreak = 'break-all';
                detailsDiv.textContent = error.details;

                link.onclick = (e) => {
                    e.preventDefault();
                    detailsDiv.style.display = 'block';
                    link.style.display = 'none';
                };

                status.appendChild(link);
                status.appendChild(detailsDiv);
            }
        }
    } finally {
        if (btn) btn.disabled = false;
    }
}

async function fetchTexturePacks() {
    try {
        const response = await fetch('/api/texture-packs', {
            headers: getAuthHeaders()
        });
        if (!response.ok) throw new Error("Failed to fetch texture packs");
        return await response.json();
    } catch (error) {
        console.error("Error fetching texture packs:", error);
        return ["default"];
    }
}