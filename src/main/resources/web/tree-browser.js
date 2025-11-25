class TreeBrowser {
    constructor() {
        this.trees = [];
        this.selectedTreeId = null;
        this.schema = null;
        this.schemaFormBuilder = null;
        this.init();
    }

    async init() {
        await this.loadSchema();
        await this.loadTrees();
        this.renderTreeList();

        // Bind search
        const searchInput = document.getElementById('tree-search');
        if (searchInput) {
            searchInput.addEventListener('input', (e) => this.filterTrees(e.target.value));
        }

        // Initial state
        this.updateDeleteButtonState();
    }

    async loadSchema() {
        // Schema removed in new architecture, using raw JSON editing
        console.log("Schema not used in new architecture");
    }

    async loadTrees() {
        try {
            const response = await fetch('/api/trees');
            if (response.ok) {
                this.trees = await response.json();
            }
        } catch (e) {
            console.error("Failed to load trees", e);
        }
    }

    renderTreeList(filter = "") {
        const list = document.getElementById('tree-list');
        if (!list) return;

        list.innerHTML = "";

        const filtered = this.trees.filter(t =>
            t.toLowerCase().includes(filter.toLowerCase())
        );

        filtered.forEach(treeId => {
            const el = document.createElement('div');
            el.className = `tree-item ${this.selectedTreeId === treeId ? 'selected' : ''}`;
            el.onclick = () => this.selectTree(treeId);
            el.innerHTML = `
                <h3>${treeId}</h3>
                <p>Custom Tree</p>
            `;
            list.appendChild(el);
        });
    }

    filterTrees(query) {
        this.renderTreeList(query);
    }

    async selectTree(treeId) {
        this.selectedTreeId = treeId;
        this.renderTreeList(); // Update selection UI

        try {
            const response = await fetch(`/api/trees/${treeId}`);
            if (response.ok) {
                const treeJson = await response.json();
                window.currentTreeJson = treeJson;

                // Load into editor
                document.getElementById('tree_name').value = treeId;
                document.getElementById('tree_description').value = "";

                // Build dynamic form from config
                this.buildEditorForm(treeJson.config || {});

                // Update JSON editor with full JSON
                document.getElementById('json-editor').value = JSON.stringify(treeJson, null, 2);

                this.updateDeleteButtonState();

                // Switch to editor
                switchTab('editor');

                // Trigger updates
                updateMaterials();
                generateTree();
            } else {
                alert("Failed to load tree");
            }
        } catch (e) {
            console.error(e);
            alert("Error loading tree");
        }
    }

    createNewTree() {
        this.selectedTreeId = null;
        this.renderTreeList(); // Clear selection

        // Reset form
        document.getElementById('tree_name').value = "";
        document.getElementById('tree_description').value = "";

        // Default config (could be extracted from schema defaults, but hardcoding a sensible starter here is fine)
        const defaultConfig = {
            trunk_provider: {
                type: "minecraft:simple_state_provider",
                state: { Name: "minecraft:oak_log" }
            },
            foliage_provider: {
                type: "minecraft:simple_state_provider",
                state: { Name: "minecraft:oak_leaves" }
            },
            trunk_placer: {
                type: "minecraft:straight_trunk_placer",
                base_height: 4,
                height_rand_a: 2,
                height_rand_b: 0
            },
            foliage_placer: {
                type: "minecraft:blob_foliage_placer",
                radius: 2,
                offset: 0,
                height: 3
            },
            minimum_size: {
                type: "minecraft:two_layers_feature_size",
                limit: 1,
                lower_size: 0,
                upper_size: 1
            },
            decorators: []
        };

        window.currentTreeJson = {
            type: "minecraft:tree",
            config: defaultConfig
        };

        this.buildEditorForm(defaultConfig);
        document.getElementById('json-editor').value = JSON.stringify(window.currentTreeJson, null, 2);

        this.updateDeleteButtonState();

        switchTab('editor');
        updateMaterials();
        generateTree();
    }

    buildEditorForm(config) {
        const container = document.getElementById('dynamic-form-container');
        container.innerHTML = ''; // Clear existing form

        // Create a simple dynamic form based on the JSON structure
        this.buildFormFromObject(config, container, '');
    }

    buildFormFromObject(obj, container, path) {
        for (const key in obj) {
            const value = obj[key];
            const currentPath = path ? `${path}.${key}` : key;

            const fieldDiv = document.createElement('div');
            fieldDiv.className = 'form-field';
            fieldDiv.innerHTML = `<label>${key}:</label>`;

            if (typeof value === 'string') {
                const input = document.createElement('input');
                input.type = 'text';
                input.value = value;
                input.dataset.path = currentPath;
                input.addEventListener('input', (e) => this.updateConfigFromForm());
                fieldDiv.appendChild(input);
            } else if (typeof value === 'number') {
                const input = document.createElement('input');
                input.type = 'number';
                input.value = value;
                input.dataset.path = currentPath;
                input.addEventListener('input', (e) => this.updateConfigFromForm());
                fieldDiv.appendChild(input);
            } else if (typeof value === 'boolean') {
                const input = document.createElement('input');
                input.type = 'checkbox';
                input.checked = value;
                input.dataset.path = currentPath;
                input.addEventListener('change', (e) => this.updateConfigFromForm());
                fieldDiv.appendChild(input);
            } else if (Array.isArray(value)) {
                // For arrays, show as JSON for now
                const textarea = document.createElement('textarea');
                textarea.value = JSON.stringify(value, null, 2);
                textarea.dataset.path = currentPath;
                textarea.rows = 3;
                textarea.addEventListener('input', (e) => this.updateConfigFromForm());
                fieldDiv.appendChild(textarea);
            } else if (typeof value === 'object' && value !== null) {
                // Nested object
                const nestedDiv = document.createElement('div');
                nestedDiv.className = 'nested-object';
                this.buildFormFromObject(value, nestedDiv, currentPath);
                fieldDiv.appendChild(nestedDiv);
            }

            container.appendChild(fieldDiv);
        }
    }

    updateConfigFromForm() {
        if (!window.currentTreeJson || !window.currentTreeJson.config) return;

        const inputs = document.querySelectorAll('#dynamic-form-container input, #dynamic-form-container textarea');
        inputs.forEach(input => {
            const path = input.dataset.path;
            if (!path) return;

            const keys = path.split('.');
            let current = window.currentTreeJson.config;

            // Navigate to the nested property
            for (let i = 0; i < keys.length - 1; i++) {
                if (!current[keys[i]]) current[keys[i]] = {};
                current = current[keys[i]];
            }

            const lastKey = keys[keys.length - 1];
            if (input.type === 'checkbox') {
                current[lastKey] = input.checked;
            } else if (input.type === 'number') {
                current[lastKey] = parseFloat(input.value) || 0;
            } else if (input.tagName === 'TEXTAREA') {
                try {
                    current[lastKey] = JSON.parse(input.value);
                } catch (e) {
                    // Invalid JSON, keep as string for now
                    current[lastKey] = input.value;
                }
            } else {
                current[lastKey] = input.value;
            }
        });
    }

    syncConfigMode(mode) {
        // Called when switching tabs
        if (mode === 'json') {
            // Form -> JSON
            this.updateConfigFromForm(); // Ensure config is updated from form
            document.getElementById('json-editor').value = JSON.stringify(window.currentTreeJson, null, 2);
        } else {
            // JSON -> Form
            try {
                const json = document.getElementById('json-editor').value;
                const fullJson = JSON.parse(json);
                window.currentTreeJson = fullJson;
                this.buildEditorForm(fullJson.config || {});
            } catch (e) {
                alert("Invalid JSON in editor. Fix errors before switching back to Form view.");
                console.error(e);
            }
        }
    }

    async saveCurrentTree() {
        const name = document.getElementById('tree_name').value.trim();
        if (!name) {
            alert('Please enter a tree name');
            return;
        }

        // Get current full JSON from active view
        let fullJson = window.currentTreeJson || { type: "minecraft:tree", config: {} };
        const activeTab = document.querySelector('.config-tab.active');
        if (activeTab && activeTab.dataset.mode === 'json') {
            try {
                fullJson = JSON.parse(document.getElementById('json-editor').value);
            } catch (e) {
                alert("Invalid JSON. Cannot save.");
                return;
            }
        } else {
            // Ensure config is updated from form
            this.updateConfigFromForm();
        }

        // Set ID
        fullJson.id = this.selectedTreeId || name.toLowerCase().replace(/ /g, '_');

        try {
            const response = await fetch(`/api/trees/${fullJson.id}`, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(fullJson)
            });

            if (response.ok) {
                const saved = await response.json();
                this.selectedTreeId = saved.id;
                window.currentTreeJson = fullJson;
                await this.loadTrees();
                this.renderTreeList();
                this.updateDeleteButtonState();
                alert('Tree saved!');
            } else {
                const error = await response.text();
                alert('Failed to save tree: ' + error);
            }
        } catch (e) {
            console.error('Error saving tree:', e);
            alert('Error saving tree: ' + e.message);
        }
    }

    async deleteSelected() {
        if (!this.selectedTreeId) {
            return;
        }

        if (!confirm("Are you sure you want to delete this tree?")) return;

        try {
            const response = await fetch(`/api/trees/${this.selectedTreeId}`, {
                method: 'DELETE'
            });

            if (response.ok) {
                this.selectedTreeId = null;
                await this.loadTrees();
                this.renderTreeList();
                this.createNewTree(); // Reset to new tree state
            } else {
                alert("Failed to delete tree");
            }
        } catch (e) {
            console.error(e);
            alert("Error deleting tree");
        }
    }

    updateDeleteButtonState() {
        const btn = document.getElementById('btn-delete');
        if (btn) {
            btn.disabled = !this.selectedTreeId;
        }
    }

    async openImportModal() {
        const modal = document.getElementById('import-modal');
        const vanillaList = document.getElementById('vanilla-list');
        const searchInput = document.getElementById('import-search');

        modal.style.display = 'flex';
        vanillaList.innerHTML = '<div style="padding: 20px; text-align: center; color: #858585;">Loading...</div>';

        try {
            const response = await fetch('/api/vanilla_trees');
            if (response.ok) {
                const trees = await response.json();
                this.renderVanillaList(trees);

                // Bind search
                searchInput.value = '';
                searchInput.oninput = (e) => {
                    const query = e.target.value.toLowerCase();
                    const filtered = trees.filter(id => id.toLowerCase().includes(query));
                    this.renderVanillaList(filtered);
                };
            } else {
                vanillaList.innerHTML = '<div style="padding: 20px; text-align: center; color: #ff6b6b;">Failed to load vanilla trees</div>';
            }
        } catch (e) {
            console.error(e);
            vanillaList.innerHTML = '<div style="padding: 20px; text-align: center; color: #ff6b6b;">Error loading vanilla trees</div>';
        }
    }

    renderVanillaList(trees) {
        const vanillaList = document.getElementById('vanilla-list');
        vanillaList.innerHTML = '';

        if (trees.length === 0) {
            vanillaList.innerHTML = '<div style="padding: 20px; text-align: center; color: #858585;">No trees found</div>';
            return;
        }

        trees.forEach(id => {
            const el = document.createElement('div');
            el.className = 'vanilla-item';
            el.textContent = id;
            el.onclick = () => this.importVanillaTree(id);
            vanillaList.appendChild(el);
        });
    }

    async importVanillaTree(id) {
        const modal = document.getElementById('import-modal');
        console.log("Importing tree:", id);

        try {
            // Fetch the raw vanilla tree JSON from Minecraft resources
            const response = await fetch(`/api/vanilla_tree/${id}`);

            if (response.ok) {
                const vanillaJson = await response.json();
                modal.style.display = 'none';

                console.log("Vanilla config loaded", vanillaJson);

                // Create the full JSON for editing
                const fullJson = {
                    type: 'minecraft:tree',
                    config: vanillaJson.config || vanillaJson  // If already wrapped, use config
                };

                this.selectedTreeId = null; // It's a new tree until saved

                const nameInput = document.getElementById('tree_name');
                const descInput = document.getElementById('tree_description');

                if (nameInput) nameInput.value = id.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
                if (descInput) descInput.value = `Imported from vanilla minecraft:${id}`;

                // Store the full JSON for editing
                window.currentTreeJson = fullJson;

                // Build dynamic form
                console.log("Building editor form...");
                this.buildEditorForm(fullJson.config);

                const jsonEditor = document.getElementById('json-editor');
                if (jsonEditor) jsonEditor.value = JSON.stringify(fullJson, null, 2);

                this.updateDeleteButtonState();

                // Switch to editor
                switchTab('editor');

                console.log("Updating materials and generating tree...");
                await updateMaterials();
                await generateTree();
                console.log("Import complete.");
            } else {
                alert(`Failed to import tree: ${await response.text()}`);
            }
        } catch (e) {
            console.error('Error importing vanilla tree:', e);
            alert('Error importing tree: ' + e.message);
        }
    }
}

// Initialize
let treeBrowser;
window.addEventListener('DOMContentLoaded', () => {
    treeBrowser = new TreeBrowser();
    window.treeBrowser = treeBrowser; // Expose to global scope for index.html calls

    // Bind buttons
    document.getElementById('btn-save')?.addEventListener('click', () => treeBrowser.saveCurrentTree());
    document.getElementById('btn-delete')?.addEventListener('click', () => treeBrowser.deleteSelected());
    document.getElementById('btn-create-new')?.addEventListener('click', () => treeBrowser.createNewTree());
    document.getElementById('btn-import')?.addEventListener('click', () => treeBrowser.openImportModal());
});
