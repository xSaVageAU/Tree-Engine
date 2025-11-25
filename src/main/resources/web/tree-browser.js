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
        try {
            const response = await fetch('/api/schema');
            if (response.ok) {
                this.schema = await response.json();
                this.schemaFormBuilder = new SchemaFormBuilder(this.schema);
                console.log("Schema loaded successfully");
            } else {
                console.error("Failed to load schema");
            }
        } catch (e) {
            console.error("Error loading schema", e);
        }
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
            (t.name && t.name.toLowerCase().includes(filter.toLowerCase())) ||
            t.id.toLowerCase().includes(filter.toLowerCase())
        );

        filtered.forEach(tree => {
            const el = document.createElement('div');
            el.className = `tree-item ${this.selectedTreeId === tree.id ? 'selected' : ''}`;
            el.onclick = () => this.selectTree(tree);
            el.innerHTML = `
                <h3>${tree.name || tree.id}</h3>
                <p>${tree.description || 'No description'}</p>
            `;
            list.appendChild(el);
        });
    }

    filterTrees(query) {
        this.renderTreeList(query);
    }

    selectTree(tree) {
        this.selectedTreeId = tree.id;
        this.renderTreeList(); // Update selection UI

        // Store the wrapper for editing
        window.currentTreeWrapper = tree;

        // Load into editor
        document.getElementById('tree_name').value = tree.name || "";
        document.getElementById('tree_description').value = tree.description || "";

        // Build dynamic form
        this.buildEditorForm(tree.config || {});

        // Update JSON editor
        document.getElementById('json-editor').value = JSON.stringify(tree.config || {}, null, 2);

        this.updateDeleteButtonState();

        // Switch to editor
        switchTab('editor');

        // Trigger updates
        updateMaterials();
        generateTree();
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

        window.currentTreeWrapper = {
            id: null,
            config: defaultConfig
        };

        this.buildEditorForm(defaultConfig);
        document.getElementById('json-editor').value = JSON.stringify(defaultConfig, null, 2);

        this.updateDeleteButtonState();

        switchTab('editor');
        updateMaterials();
        generateTree();
    }

    buildEditorForm(config) {
        if (!this.schemaFormBuilder) {
            console.error("Schema builder not initialized");
            return;
        }
        const container = document.getElementById('dynamic-form-container');
        this.schemaFormBuilder.buildForm(config, container);
    }

    syncConfigMode(mode) {
        // Called when switching tabs
        if (mode === 'json') {
            // Form -> JSON
            const container = document.getElementById('dynamic-form-container');
            if (this.schemaFormBuilder) {
                const config = this.schemaFormBuilder.extractValues(container);
                document.getElementById('json-editor').value = JSON.stringify(config, null, 2);

                // Update wrapper config
                if (window.currentTreeWrapper) {
                    window.currentTreeWrapper.config = config;
                }
            }
        } else {
            // JSON -> Form
            try {
                const json = document.getElementById('json-editor').value;
                const config = JSON.parse(json);
                this.buildEditorForm(config);

                // Update wrapper config
                if (window.currentTreeWrapper) {
                    window.currentTreeWrapper.config = config;
                }
            } catch (e) {
                alert("Invalid JSON in editor. Fix errors before switching back to Form view.");
                // Prevent switch? For now just alert.
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

        // Get current config from active view
        let config = {};
        const activeTab = document.querySelector('.config-tab.active');
        if (activeTab && activeTab.dataset.mode === 'json') {
            try {
                config = JSON.parse(document.getElementById('json-editor').value);
            } catch (e) {
                alert("Invalid JSON. Cannot save.");
                return;
            }
        } else {
            // Extract from form
            const container = document.getElementById('dynamic-form-container');
            if (this.schemaFormBuilder) {
                config = this.schemaFormBuilder.extractValues(container);
            }
        }

        // Get existing wrapper or create new one
        let wrapper = window.currentTreeWrapper || {};

        // Update metadata
        wrapper.id = wrapper.id || name.toLowerCase().replace(/ /g, '_');
        wrapper.name = name;
        wrapper.description = document.getElementById('tree_description').value;
        wrapper.namespace = wrapper.namespace || 'tree_engine';
        wrapper.version = wrapper.version || '1.0';
        wrapper.type = 'minecraft:tree';
        wrapper.config = config;

        try {
            const response = await fetch('/api/trees', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(wrapper)
            });

            if (response.ok) {
                const saved = await response.json();
                this.selectedTreeId = saved.id;
                window.currentTreeWrapper = saved;
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
                const vanillaConfig = await response.json();
                modal.style.display = 'none';

                console.log("Vanilla config loaded", vanillaConfig);

                // Create a TreeWrapper with the vanilla config
                const wrapper = {
                    id: id + '_import',
                    name: id.replace(/_/g, ' ').replace(/\b\w/g, c => c.toUpperCase()),
                    description: `Imported from vanilla minecraft:${id}`,
                    namespace: 'tree_engine',
                    version: '1.0',
                    type: 'minecraft:tree',
                    config: vanillaConfig.config || vanillaConfig  // Extract config if wrapped
                };

                this.selectedTreeId = null; // It's a new tree until saved

                const nameInput = document.getElementById('tree_name');
                const descInput = document.getElementById('tree_description');

                if (nameInput) nameInput.value = wrapper.name;
                if (descInput) descInput.value = wrapper.description;

                // Store the wrapper for later saving
                window.currentTreeWrapper = wrapper;

                // Build dynamic form
                console.log("Building editor form...");
                this.buildEditorForm(wrapper.config);

                const jsonEditor = document.getElementById('json-editor');
                if (jsonEditor) jsonEditor.value = JSON.stringify(wrapper.config, null, 2);

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
