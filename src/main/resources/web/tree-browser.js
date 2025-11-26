class TreeBrowser {
    constructor() {
        this.trees = [];
        this.selectedTreeId = null;
        this.monacoEditor = null;
        this.isUpdatingEditor = false; // Flag to prevent auto-regeneration during programmatic updates
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

        // Initialize Monaco Editor
        this.initMonacoEditor();

        // Initial state
        this.updateDeleteButtonState();
    }

    initMonacoEditor() {
        // Load Monaco Editor
        require.config({ paths: { vs: 'https://cdnjs.cloudflare.com/ajax/libs/monaco-editor/0.44.0/min/vs' } });

        require(['vs/editor/editor.main'], () => {
            this.monacoEditor = monaco.editor.create(document.getElementById('monaco-container'), {
                value: '{}',
                language: 'json',
                theme: 'vs-dark',
                automaticLayout: true,
                minimap: { enabled: false },
                fontSize: 13,
                tabSize: 2,
                insertSpaces: true
            });

            // Add auto-regeneration with debounce
            let debounceTimer = null;
            this.monacoEditor.onDidChangeModelContent(() => {
                // Skip if we're programmatically updating the editor
                if (this.isUpdatingEditor) {
                    return;
                }

                // Clear existing timer
                if (debounceTimer) {
                    clearTimeout(debounceTimer);
                }

                // Set new timer for 500ms
                debounceTimer = setTimeout(() => {
                    try {
                        const jsonText = this.monacoEditor.getValue();
                        const parsedJson = JSON.parse(jsonText);

                        // Update current tree JSON
                        window.currentTreeJson = parsedJson;

                        // Regenerate tree preview
                        if (typeof generateTree === 'function') {
                            generateTree();
                        }
                    } catch (e) {
                        // Invalid JSON, don't regenerate
                        console.log("Invalid JSON, skipping regeneration:", e.message);
                    }
                }, 500);
            });
        });
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

                // Show settings panel and populate
                this.showSettings(treeId, treeJson);

                this.updateDeleteButtonState();

                // Trigger generation (don't let errors break tree loading)
                try {
                    await generateTree();
                } catch (genError) {
                    console.warn("Initial generation failed, but tree loaded:", genError);
                }
            } else {
                alert("Failed to load tree");
            }
        } catch (e) {
            console.error(e);
            alert("Error loading tree");
        }
    }

    showSettings(treeId, treeJson) {
        // Hide library, show settings
        const librarySection = document.getElementById('library-section');
        const settingsPanel = document.getElementById('settings-panel');

        librarySection.style.display = 'none';
        settingsPanel.style.display = 'flex';

        // Populate settings
        document.getElementById('tree_name').value = treeId;
        document.getElementById('tree_description').value = "";

        // Update Monaco editor content if it exists
        if (this.monacoEditor) {
            this.isUpdatingEditor = true;
            this.monacoEditor.setValue(JSON.stringify(treeJson, null, 2));
            // Reset flag after a short delay to allow the change event to process
            setTimeout(() => {
                this.isUpdatingEditor = false;
            }, 100);
        }
    }

    hideSettings() {
        // Show library, hide settings
        const librarySection = document.getElementById('library-section');
        const settingsPanel = document.getElementById('settings-panel');

        librarySection.style.display = 'flex';
        settingsPanel.style.display = 'none';
    }

    openJsonEditor() {
        const bottomPanel = document.getElementById('bottom-panel');
        bottomPanel.classList.add('open');

        // Ensure Monaco editor has the current tree JSON
        if (this.monacoEditor && window.currentTreeJson) {
            this.isUpdatingEditor = true;
            this.monacoEditor.setValue(JSON.stringify(window.currentTreeJson, null, 2));
            // Reset flag after a short delay to allow the change event to process
            setTimeout(() => {
                this.isUpdatingEditor = false;
            }, 100);
        }
    }

    closeJsonEditor() {
        const bottomPanel = document.getElementById('bottom-panel');
        bottomPanel.classList.remove('open');

        // Sync changes from Monaco back to currentTreeJson
        if (this.monacoEditor) {
            try {
                const jsonText = this.monacoEditor.getValue();
                window.currentTreeJson = JSON.parse(jsonText);
            } catch (e) {
                console.error("Invalid JSON in Monaco editor:", e);
                alert("Invalid JSON in editor. Please fix errors before closing.");
                // Re-open the panel
                bottomPanel.classList.add('open');
            }
        }
    }

    createNewTree() {
        this.selectedTreeId = null;
        this.renderTreeList(); // Clear selection

        // Default config
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

        // Show settings with empty name
        this.showSettings("", window.currentTreeJson);
        document.getElementById('tree_name').value = "";

        this.updateDeleteButtonState();

        generateTree();
    }

    async saveCurrentTree() {
        const name = document.getElementById('tree_name').value.trim();
        if (!name) {
            alert('Please enter a tree name');
            return;
        }

        // Get current JSON from Monaco editor if it's open
        const bottomPanel = document.getElementById('bottom-panel');
        if (bottomPanel.classList.contains('open') && this.monacoEditor) {
            try {
                const jsonText = this.monacoEditor.getValue();
                window.currentTreeJson = JSON.parse(jsonText);
            } catch (e) {
                alert("Invalid JSON in editor. Cannot save.");
                return;
            }
        }

        let fullJson = window.currentTreeJson || { type: "minecraft:tree", config: {} };

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
                this.hideSettings();
                this.updateDeleteButtonState();
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

        // Add manual lookup section if not present
        let manualSection = document.getElementById('manual-import-section');
        if (!manualSection) {
            manualSection = document.createElement('div');
            manualSection.id = 'manual-import-section';
            manualSection.style.padding = '10px';
            manualSection.style.borderBottom = '1px solid #3c3c3c';
            manualSection.style.marginBottom = '10px';
            manualSection.style.display = 'flex';
            manualSection.style.gap = '10px';

            manualSection.innerHTML = `
                <input type="text" id="manual-import-input" placeholder="Enter ID (e.g. tree_engine:oak)" style="flex: 3; min-width: 200px; padding: 5px; background: #252526; border: 1px solid #3c3c3c; color: #ccc;">
                <button id="btn-manual-import" style="flex: 1; padding: 5px 5px; background: #0e639c; color: white; border: none; cursor: pointer;">Lookup</button>
            `;

            // Insert before the list
            vanillaList.parentNode.insertBefore(manualSection, vanillaList);

            // Bind button
            document.getElementById('btn-manual-import').onclick = () => {
                const id = document.getElementById('manual-import-input').value.trim();
                if (id) this.importVanillaTree(id);
            };
        }

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
        }

        trees.forEach(id => {
            const el = document.createElement('div');
            el.className = 'vanilla-item';

            // Display clean name but store full ID
            const displayName = id.startsWith('minecraft:') ? id.split(':')[1] : id;

            el.textContent = displayName;
            el.title = id; // Tooltip shows full ID

            el.onclick = () => this.importVanillaTree(id);
            vanillaList.appendChild(el);
        });
    }

    async importVanillaTree(id) {
        const modal = document.getElementById('import-modal');
        console.log("Importing tree:", id);

        // Show loading state
        const vanillaList = document.getElementById('vanilla-list');
        const originalContent = vanillaList.innerHTML;
        vanillaList.innerHTML = '<div style="padding: 20px; text-align: center; color: #858585;">Importing...</div>';

        try {
            const response = await fetch(`/api/vanilla_tree/${id}`);

            if (response.ok) {
                const treeJson = await response.json();

                // Load into editor
                window.currentTreeJson = treeJson;

                // Set name to the imported ID (cleaned up)
                const name = id.split(':')[1] || id;

                // Show settings
                this.selectedTreeId = null; // It's a new unsaved tree
                this.showSettings(name, treeJson);
                document.getElementById('tree_name').value = name;

                const descInput = document.getElementById('tree_description');
                if (descInput) descInput.value = `Imported from ${id}`;

                this.updateDeleteButtonState();

                // Close modal
                modal.style.display = 'none';

                // Trigger generation
                if (typeof generateTree === 'function') {
                    generateTree();
                }

                console.log("Import complete.");
            } else {
                alert(`Failed to import tree: ${await response.text()}`);
                vanillaList.innerHTML = originalContent; // Restore list on error
            }
        } catch (e) {
            console.error('Error importing vanilla tree:', e);
            alert('Error importing tree: ' + e.message);
            vanillaList.innerHTML = originalContent; // Restore list on error
        }
    }
}

window.addEventListener('DOMContentLoaded', () => {
    treeBrowser = new TreeBrowser();
    window.treeBrowser = treeBrowser; // Expose to global scope

    // Bind buttons
    document.getElementById('btn-save')?.addEventListener('click', () => treeBrowser.saveCurrentTree());
    document.getElementById('btn-delete')?.addEventListener('click', () => treeBrowser.deleteSelected());
    document.getElementById('btn-create-new')?.addEventListener('click', () => treeBrowser.createNewTree());
    document.getElementById('btn-import')?.addEventListener('click', () => treeBrowser.openImportModal());
    document.getElementById('btn-edit-json')?.addEventListener('click', () => treeBrowser.openJsonEditor());
    document.getElementById('btn-close-editor')?.addEventListener('click', () => treeBrowser.closeJsonEditor());
    document.getElementById('btn-back-to-library')?.addEventListener('click', () => treeBrowser.hideSettings());
});
