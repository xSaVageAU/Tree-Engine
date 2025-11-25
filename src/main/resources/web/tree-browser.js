class TreeBrowser {
    constructor() {
        this.trees = [];
        this.selectedTreeId = null;
        this.init();
    }

    async init() {
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

        // Load into editor - extract from config if available
        document.getElementById('tree_name').value = tree.name || "";

        // Try to extract block info from config
        const config = tree.config || {};
        if (config.trunk_provider && config.trunk_provider.state) {
            document.getElementById('trunk_block').value = config.trunk_provider.state.Name || 'minecraft:oak_log';
        }
        if (config.foliage_provider && config.foliage_provider.state) {
            document.getElementById('foliage_block').value = config.foliage_provider.state.Name || 'minecraft:oak_leaves';
        }

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
        document.getElementById('trunk_block').value = "minecraft:oak_log";
        document.getElementById('foliage_block').value = "minecraft:oak_leaves";
        document.getElementById('trunk_height_min').value = 4;
        document.getElementById('trunk_height_max').value = 6;
        document.getElementById('foliage_radius').value = 2;
        document.getElementById('foliage_offset').value = 0;

        // Reset placer types
        document.getElementById('trunk_placer_type').value = "straight";
        document.getElementById('foliage_placer_type').value = "blob";
        document.getElementById('foliage_height').value = 3;

        // Update displays
        document.getElementById('height_min_val').textContent = 4;
        document.getElementById('height_max_val').textContent = 6;
        document.getElementById('radius_val').textContent = 2;
        document.getElementById('offset_val').textContent = 0;
        document.getElementById('foliage_height_val').textContent = 3;

        this.updateDeleteButtonState();

        switchTab('editor');
        updateMaterials();
        generateTree();
    }

    async saveCurrentTree() {
        const name = document.getElementById('tree_name').value.trim();
        if (!name) {
            alert('Please enter a tree name');
            return;
        }

        // Get existing wrapper or create new one
        let wrapper = window.currentTreeWrapper || {
            id: name.toLowerCase().replace(/ /g, '_'),
            namespace: 'tree_engine',
            version: '1.0',
            type: 'minecraft:tree',
            config: {}
        };

        // Update metadata
        wrapper.name = name;
        wrapper.description = `Custom tree: ${name}`;

        // Build simple config from form values (temporary until dynamic form is complete)
        const trunkBlock = document.getElementById('trunk_block').value;
        const foliageBlock = document.getElementById('foliage_block').value;

        wrapper.config = {
            trunk_provider: {
                type: 'minecraft:simple_state_provider',
                state: { Name: trunkBlock }
            },
            foliage_provider: {
                type: 'minecraft:simple_state_provider',
                state: { Name: foliageBlock }
            },
            trunk_placer: {
                type: `minecraft:${document.getElementById('trunk_placer_type').value}_trunk_placer`,
                base_height: parseInt(document.getElementById('trunk_height_min').value),
                height_rand_a: parseInt(document.getElementById('trunk_height_max').value) - parseInt(document.getElementById('trunk_height_min').value),
                height_rand_b: 0
            },
            foliage_placer: {
                type: `minecraft:${document.getElementById('foliage_placer_type').value}_foliage_placer`,
                radius: parseInt(document.getElementById('foliage_radius').value),
                offset: parseInt(document.getElementById('foliage_offset').value),
                height: parseInt(document.getElementById('foliage_height').value)
            },
            minimum_size: {
                type: 'minecraft:two_layers_feature_size',
                limit: 1,
                lower_size: 0,
                upper_size: 1
            },
            decorators: []
        };

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

        try {
            // Fetch the raw vanilla tree JSON from Minecraft resources
            const response = await fetch(`/api/vanilla_tree/${id}`);

            if (response.ok) {
                const vanillaConfig = await response.json();
                modal.style.display = 'none';

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

                // For now, populate the static form with defaults (will be replaced by dynamic form later)
                // This is a temporary solution until we complete the dynamic form integration
                this.selectedTreeId = null; // It's a new tree until saved
                document.getElementById('tree_name').value = wrapper.name;

                // Try to extract some basic info from the config for the static form
                const config = wrapper.config;
                if (config.trunk_provider && config.trunk_provider.state) {
                    document.getElementById('trunk_block').value = config.trunk_provider.state.Name || 'minecraft:oak_log';
                }
                if (config.foliage_provider && config.foliage_provider.state) {
                    document.getElementById('foliage_block').value = config.foliage_provider.state.Name || 'minecraft:oak_leaves';
                }

                // Store the wrapper for later saving
                window.currentTreeWrapper = wrapper;

                this.updateDeleteButtonState();

                // Switch to editor
                switchTab('editor');
                updateMaterials();
                generateTree();
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

    // Bind buttons
    document.getElementById('btn-save')?.addEventListener('click', () => treeBrowser.saveCurrentTree());
    document.getElementById('btn-delete')?.addEventListener('click', () => treeBrowser.deleteSelected());
    document.getElementById('btn-create-new')?.addEventListener('click', () => treeBrowser.createNewTree());
    document.getElementById('btn-import')?.addEventListener('click', () => treeBrowser.openImportModal());
});
