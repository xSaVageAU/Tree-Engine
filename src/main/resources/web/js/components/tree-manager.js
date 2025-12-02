class TreeManager {
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
            const response = await fetch('/api/trees', {
                headers: getAuthHeaders()
            });
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
            const response = await fetch(`/api/trees/${treeId}`, {
                headers: getAuthHeaders()
            });
            if (response.ok) {
                const treeJson = await response.json();
                window.currentTreeJson = treeJson;

                // Also load the PlacedFeature
                fetch(`/api/trees/${treeId}/placement`, {
                    headers: getAuthHeaders()
                })
                    .then(r => r.json())
                    .then(placementJson => {
                        window.currentPlacedFeatureJson = placementJson;
                    })
                    .catch(err => {
                        console.error('Failed to load placement:', err);
                        // Create default if not exists
                        window.currentPlacedFeatureJson = {
                            feature: `tree_engine:${treeId}`,
                            placement: []
                        };
                    });

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
        // Hide library and replacers, show settings
        const librarySection = document.getElementById('library-section');
        const settingsPanel = document.getElementById('settings-panel');
        const replacersPanel = document.getElementById('replacers-panel');

        librarySection.style.display = 'none';
        settingsPanel.style.display = 'flex';
        replacersPanel.style.display = 'none';

        // Populate settings
        document.getElementById('tree_name').value = treeId;
        document.getElementById('tree_description').value = "";

        // Update Monaco editor content if it exists
        if (window.editorManager && window.editorManager.monacoEditor) {
            window.editorManager.isUpdatingEditor = true;
            window.editorManager.monacoEditor.setValue(JSON.stringify(treeJson, null, 2));
            // Reset flag after a short delay to allow the change event to process
            setTimeout(() => {
                window.editorManager.isUpdatingEditor = false;
            }, 100);
        }
    }

    hideSettings() {
        // Show library, hide settings
        const librarySection = document.getElementById('library-section');
        const settingsPanel = document.getElementById('settings-panel');
        librarySection.style.display = 'flex';
        settingsPanel.style.display = 'none';
        // Clear the 3D renderer
        if (typeof clearScene === 'function') {
            clearScene();
        }
        // Close and reset the Monaco editor
        if (window.editorManager) {
            const bottomPanel = document.getElementById('bottom-panel');
            if (bottomPanel.classList.contains('open')) {
                window.editorManager.closeJsonEditor();
            }
        }
        // Clear tree selection
        this.selectedTreeId = null;
        this.renderTreeList();
    }

    showReplacers() {
        // Hide library and settings, show replacers
        const librarySection = document.getElementById('library-section');
        const settingsPanel = document.getElementById('settings-panel');
        const replacersPanel = document.getElementById('replacers-panel');

        librarySection.style.display = 'none';
        settingsPanel.style.display = 'none';
        replacersPanel.style.display = 'flex';
    }

    hideReplacers() {
        // Show library, hide replacers
        const librarySection = document.getElementById('library-section');
        const replacersPanel = document.getElementById('replacers-panel');

        librarySection.style.display = 'flex';
        replacersPanel.style.display = 'none';
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
            dirt_provider: {
                type: "minecraft:simple_state_provider",
                state: { Name: "minecraft:dirt" }
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
        window.currentPlacedFeatureJson = null;

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

        // Validate name (alphanumeric and underscores only)
        if (!/^[a-zA-Z0-9_]+$/.test(name)) {
            alert("Tree name can only contain letters, numbers, and underscores.");
            return;
        }

        // Get current JSON from Monaco editor if it's open
        const bottomPanel = document.getElementById('bottom-panel');
        if (bottomPanel.classList.contains('open') && window.editorManager && window.editorManager.monacoEditor) {
            try {
                const jsonText = window.editorManager.monacoEditor.getValue();
                const parsedJson = JSON.parse(jsonText);

                // Save to the correct variable based on current mode
                if (window.editorManager.currentMode === 'TREE') {
                    window.currentTreeJson = parsedJson;
                } else {
                    window.currentPlacedFeatureJson = parsedJson;
                }
            } catch (e) {
                alert("Invalid JSON in editor. Cannot save.");
                return;
            }
        }

        let fullJson = window.currentTreeJson || { type: "minecraft:tree", config: {} };

        // Remove 'id' field if it exists in the JSON (it shouldn't be saved)
        if (fullJson.id) {
            delete fullJson.id;
        }

        // Use the name from the input as the ID
        const newId = name.toLowerCase();

        try {
            const response = await fetch(`/api/trees/${newId}`, {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify(fullJson)
            });

            if (response.ok) {
                const saved = await response.json();
                this.selectedTreeId = saved.id;
                window.currentTreeJson = fullJson;
                await this.loadTrees();
                this.renderTreeList();
                this.updateDeleteButtonState();

                // Trigger hot reload
                try {
                    await fetch('/api/hot-reload', {
                        method: 'POST',
                        headers: getAuthHeaders()
                    });
                    alert('Tree saved and hot-reloaded!');
                } catch (reloadError) {
                    console.warn('Hot reload failed, but tree was saved:', reloadError);
                    alert('Tree saved! (Hot reload failed)');
                }
            } else {
                const error = await response.text();
                alert('Failed to save tree: ' + error);
            }
        } catch (e) {
            console.error('Error saving tree:', e);
            alert('Error saving tree: ' + e.message);
        }

        // Also save the PlacedFeature if it exists
        if (window.currentPlacedFeatureJson) {
            fetch(`/api/trees/${this.selectedTreeId}/placement`, {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify(window.currentPlacedFeatureJson)
            }).catch(err => {
                console.error('Failed to save placement:', err);
            });
        }
    }

    async deleteSelected() {
        if (!this.selectedTreeId) {
            return;
        }

        if (!confirm("Are you sure you want to delete this tree?")) return;

        try {
            const response = await fetch(`/api/trees/${this.selectedTreeId}`, {
                method: 'DELETE',
                headers: getAuthHeaders()
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
            const response = await fetch('/api/vanilla_trees', {
                headers: getAuthHeaders()
            });
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
            const response = await fetch(`/api/vanilla_tree/${id}`, {
                headers: getAuthHeaders()
            });

            if (response.ok) {
                const treeJson = await response.json();

                // Load into editor
                window.currentTreeJson = treeJson;
                window.currentPlacedFeatureJson = null;

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

    initBenchmark() {
        const btn = document.getElementById('btn-benchmark');
        if (btn) {
            btn.onclick = () => this.openBenchmarkModal();
        }

        const runBtn = document.getElementById('btn-run-benchmark');
        if (runBtn) {
            runBtn.onclick = () => this.runBenchmark();
        }
    }

    openBenchmarkModal() {
        const modal = document.getElementById('benchmark-modal');
        const results = document.getElementById('benchmark-results');

        // Reset UI
        results.style.display = 'none';
        results.innerHTML = '';

        modal.style.display = 'flex';
    }

    async runBenchmark() {
        const iterationsInput = document.getElementById('benchmark-iterations');
        const resultsDiv = document.getElementById('benchmark-results');
        const runBtn = document.getElementById('btn-run-benchmark');

        const iterations = parseInt(iterationsInput.value) || 1000;

        // UI Loading State
        runBtn.disabled = true;
        runBtn.textContent = "Running...";
        resultsDiv.style.display = 'block';
        resultsDiv.innerHTML = '<div style="color: #ccc;">Running benchmark (' + iterations + ' iterations)...</div>';

        try {
            // Get current tree config
            // Use editor content if available, otherwise currentTreeJson
            let treeConfig = window.currentTreeJson;

            if (window.editorManager && window.editorManager.monacoEditor) {
                try {
                    treeConfig = JSON.parse(window.editorManager.monacoEditor.getValue());
                } catch (e) {
                    // Ignore, use currentTreeJson
                }
            }

            // Extract feature config (remove wrapper if present)
            let feature = treeConfig;

            const response = await fetch('/api/benchmark', {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify({
                    feature: feature,
                    iterations: iterations
                })
            });

            if (response.ok) {
                const data = await response.json();

                // Display Results
                resultsDiv.innerHTML = `
                    <div style="display: grid; grid-template-columns: 1fr 1fr; gap: 10px;">
                        <div style="background: #1e1e1e; padding: 10px; border-radius: 4px;">
                            <div style="font-size: 11px; color: #858585;">Trees / Second</div>
                            <div style="font-size: 18px; color: #4ec9b0; font-weight: bold;">${Math.round(data.treesPerSecond).toLocaleString()}</div>
                        </div>
                        <div style="background: #1e1e1e; padding: 10px; border-radius: 4px;">
                            <div style="font-size: 11px; color: #858585;">Avg Time</div>
                            <div style="font-size: 18px; color: #ce9178; font-weight: bold;">${data.avgTimeMs.toFixed(3)} ms</div>
                        </div>
                        <div style="background: #1e1e1e; padding: 10px; border-radius: 4px; grid-column: span 2;">
                            <div style="font-size: 11px; color: #858585;">Total Time (${data.iterations} trees)</div>
                            <div style="font-size: 14px; color: #dcdcaa;">${data.totalTimeMs.toFixed(1)} ms</div>
                        </div>
                    </div>
                `;
            } else {
                const error = await response.text();
                resultsDiv.innerHTML = `<div style="color: #f48771;">Error: ${error}</div>`;
            }
        } catch (e) {
            console.error(e);
            resultsDiv.innerHTML = `<div style="color: #f48771;">Error: ${e.message}</div>`;
        } finally {
            runBtn.disabled = false;
            runBtn.textContent = "Run Benchmark";
        }
    }
}