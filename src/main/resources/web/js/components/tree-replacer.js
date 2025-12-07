/**
 * Tree Replacer UI Component
 * Handles the tree replacer panel functionality.
 */

class TreeReplacerUI {
    constructor() {
        this.replacers = [];
        this.vanillaTrees = [];
        this.customTrees = [];
        this.currentReplacer = null;
        this.init();
    }

    async init() {
        // Set up event listeners
        document.getElementById('btn-tree-replacers').addEventListener('click', () => {
            this.showReplacersPanel();
        });

        document.getElementById('btn-back-to-library-from-replacers').addEventListener('click', () => {
            this.hideReplacersPanel();
        });

        // Load data
        await this.loadVanillaTrees();
        await this.loadCustomTrees();
        await this.loadReplacers();

        // Render the panel
        this.renderReplacersPanel();
    }

    async loadVanillaTrees() {
        try {
            const response = await fetch('/api/vanilla_trees', {
                headers: getAuthHeaders()
            });
            this.vanillaTrees = await response.json();
        } catch (error) {
            console.error('Failed to load vanilla trees:', error);
        }
    }

    async loadCustomTrees() {
        try {
            const response = await fetch('/api/trees', {
                headers: getAuthHeaders()
            });
            this.customTrees = await response.json();
        } catch (error) {
            console.error('Failed to load custom trees:', error);
        }
    }

    async loadReplacers() {
        try {
            const response = await fetch('/api/replacers', {
                headers: getAuthHeaders()
            });
            this.replacers = await response.json();
        } catch (error) {
            console.error('Failed to load tree replacers:', error);
            this.replacers = [];
        }
    }

    showReplacersPanel() {
        document.getElementById('library-section').style.display = 'none';
        document.getElementById('settings-panel').style.display = 'none';
        document.getElementById('replacers-panel').style.display = 'flex';
        this.renderReplacersPanel();
    }

    hideReplacersPanel() {
        document.getElementById('replacers-panel').style.display = 'none';
        document.getElementById('library-section').style.display = 'flex';
    }

    renderReplacersPanel() {
        const panel = document.getElementById('replacers-panel');
        const scrollContent = panel.querySelector('.scroll-content');

        scrollContent.innerHTML = `
            <div style="padding: 15px;">
                <button id="btn-create-replacer" style="width: 100%; margin-bottom: 15px;">+ Create New Replacer</button>
                
                <div id="replacers-list">
                    ${this.replacers.length === 0
                ? '<p style="padding: 20px; color: #858585; text-align: center;">No tree replacers yet. Create one to get started!</p>'
                : this.replacers.map(r => this.renderReplacerItem(r)).join('')
            }
                </div>
            </div>
        `;

        // Add event listener for create button
        const createBtn = document.getElementById('btn-create-replacer');
        if (createBtn) {
            createBtn.addEventListener('click', () => this.showCreateReplacerForm());
        }

        // Add event listeners for edit/delete buttons
        this.replacers.forEach(replacer => {
            const editBtn = document.getElementById(`edit-replacer-${replacer.id}`);
            const deleteBtn = document.getElementById(`delete-replacer-${replacer.id}`);

            if (editBtn) {
                editBtn.addEventListener('click', () => this.editReplacer(replacer));
            }
            if (deleteBtn) {
                deleteBtn.addEventListener('click', () => this.deleteReplacer(replacer.id));
            }
        });
    }

    renderReplacerItem(replacer) {
        const type = replacer.type || 'WEIGHTED';
        let details = '';

        if (type === 'WEIGHTED') {
            const altText = replacer.alternatives
                ? replacer.alternatives.map(alt =>
                    `<span style="display: inline-block; background: #2a2a2a; padding: 2px 8px; margin: 2px; border-radius: 3px; font-size: 11px;">${alt.feature} (${(alt.chance * 100).toFixed(0)}%)</span>`
                ).join('')
                : 'No alternatives';
            details = `<strong>Default: ${replacer.default_tree || 'Not set'}</strong><br><strong>Alternatives:</strong> ${altText}`;
        } else if (type === 'SIMPLE') {
            const poolText = replacer.features
                ? replacer.features.map(feature => {
                    const treeName = feature.split(':')[1] || feature;
                    return `<span style="display: inline-block; background: #2a2a2a; padding: 2px 8px; margin: 2px; border-radius: 3px; font-size: 11px;">${treeName}</span>`;
                }).join('')
                : 'No trees in pool';
            details = `<strong>Pool:</strong> ${poolText}`;
        }

        return `
            <div class="replacer-item" style="background: #1a1a1a; padding: 15px; margin-bottom: 10px; border-radius: 5px; border: 1px solid #333;">
                <div style="margin-bottom: 10px;">
                    <h4 style="margin: 0 0 5px 0; color: #fff;">${this.getTreeDisplayName(replacer.vanilla_tree_id)}</h4>
                    <p style="margin: 0; color: #858585; font-size: 12px;">${replacer.vanilla_tree_id}</p>
                    <p style="margin: 5px 0 0 0; color: #aaa; font-size: 11px;">Type: ${type === 'WEIGHTED' ? 'Weighted' : 'Simple'}</p>
                </div>
                <div style="display: flex; justify-content: center; gap: 5px; margin-bottom: 10px;">
                    <button id="edit-replacer-${replacer.id}" class="secondary" style="padding: 5px 10px; font-size: 12px;">Edit</button>
                    <button id="delete-replacer-${replacer.id}" class="secondary" style="padding: 5px 10px; font-size: 12px;">Delete</button>
                </div>
                <div style="color: #aaa; font-size: 13px;">
                    ${details}
                </div>
            </div>
        `;
    }

    getTreeDisplayName(treeId) {
        // Extract the tree name from the ID (e.g., "minecraft:oak" -> "Oak")
        const parts = treeId.split(':');
        const name = parts[parts.length - 1];
        return name.split('_').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
    }

    showCreateReplacerForm() {
        this.currentReplacer = {
            id: '',
            vanilla_tree_id: '',
            default_tree: '',
            alternatives: [],
            type: 'WEIGHTED'
        };
        this.renderReplacerForm();
    }

    editReplacer(replacer) {
        this.currentReplacer = JSON.parse(JSON.stringify(replacer)); // Deep copy
        if (!this.currentReplacer.type) {
            this.currentReplacer.type = 'WEIGHTED';
        }
        this.renderReplacerForm();
    }

    renderReplacerForm() {
        const panel = document.getElementById('replacers-panel');
        const scrollContent = panel.querySelector('.scroll-content');

        scrollContent.innerHTML = `
            <div style="padding: 15px;">
                <h3 style="margin-top: 0;">${this.currentReplacer.id ? 'Edit' : 'Create'} Tree Replacer</h3>

                <div class="control-group">
                    <label>Replacer Type</label>
                    <select id="replacer-type-select">
                        <option value="WEIGHTED" ${this.currentReplacer.type !== 'SIMPLE' ? 'selected' : ''}>Weighted (Default + Chances)</option>
                        <option value="SIMPLE" ${this.currentReplacer.type === 'SIMPLE' ? 'selected' : ''}>Simple (Equal Chance Pool)</option>
                    </select>
                </div>

                <div class="control-group">
                    <label>Vanilla Tree to Replace</label>
                    <select id="replacer-vanilla-tree" ${this.currentReplacer.id ? 'disabled' : ''}>
                        <option value="">Select a vanilla tree...</option>
                        ${this.vanillaTrees.map(treeId => `
                            <option value="${treeId}" ${this.currentReplacer.vanilla_tree_id === treeId ? 'selected' : ''}>
                                ${this.getTreeDisplayName(treeId)} (${treeId})
                            </option>
                        `).join('')}
                    </select>
                    ${this.currentReplacer.id ? '<p style="color: #858585; font-size: 12px; margin: 5px 0 0 0;">Cannot change vanilla tree for existing replacer</p>' : ''}
                </div>

                <div id="weighted-section">
                    <div class="control-group">
                        <label>Default Tree</label>
                        <p style="color: #858585; font-size: 12px; margin: 0 0 10px 0;">The fallback tree (used when no alternative is selected)</p>
                        <select id="default-tree-select">
                            <option value="">Select default tree...</option>
                            ${this.customTrees.map(tree => `<option value="tree_engine:${tree}">${tree}</option>`).join('')}
                        </select>
                    </div>

                    <div class="control-group">
                        <label>Weighted Alternatives</label>
                        <p style="color: #858585; font-size: 12px; margin: 0 0 10px 0;">Trees that can replace the default with a specific probability</p>

                        <div id="alternatives-list" style="max-height: 200px; overflow-y: auto; border: 1px solid #333; border-radius: 3px; background: #1a1a1a; padding: 10px;">
                            ${this.customTrees.length === 0
                ? '<p style="color: #858585; text-align: center; margin: 0;">No custom trees available. Create some trees first!</p>'
                : ''
            }
                        </div>
                        <button id="btn-add-alternative" style="width: 100%; margin-top: 10px;">+ Add Alternative</button>
                        <p style="font-size: 0.9em; color: #888; margin-top: 5px;">
                            💡 Tip: Chance is the probability this tree replaces the default (0.0-1.0). Example: 0.3 = 30% chance
                        </p>
                    </div>
                </div>

                <div id="simple-section" style="display: none;">
                    <div class="control-group">
                        <label>Tree Pool</label>
                        <p style="color: #858585; font-size: 12px; margin: 0 0 10px 0;">All trees in this pool have an equal chance of being picked.</p>

                        <div id="simple-pool-list" style="max-height: 200px; overflow-y: auto; border: 1px solid #333; border-radius: 3px; background: #1a1a1a; padding: 10px;">
                            <!-- Simple pool entries go here -->
                        </div>
                        <button id="btn-add-pool-tree" style="width: 100%; margin-top: 10px;">+ Add Tree to Pool</button>
                    </div>
                </div>

                <div style="margin-top: 20px; display: flex; gap: 10px;">
                    <button id="btn-save-replacer" style="flex: 1;">Save Replacer</button>
                    <button id="btn-cancel-replacer" class="secondary" style="flex: 1;">Cancel</button>
                </div>
            </div>
        `;

        // Add event listeners
        document.getElementById('btn-save-replacer').addEventListener('click', () => this.saveReplacer());
        document.getElementById('btn-cancel-replacer').addEventListener('click', () => {
            this.currentReplacer = null;
            this.renderReplacersPanel();
        });

        // Add event listener for add button
        document.getElementById('btn-add-alternative').addEventListener('click', () => this.addAlternative());

        // Add event listener for type selector
        document.getElementById('replacer-type-select').addEventListener('change', (e) => this.toggleReplacerSections(e.target.value));

        // Add event listener for add pool tree button
        document.getElementById('btn-add-pool-tree').addEventListener('click', () => this.addSimplePoolEntry());

        // Populate existing data
        this.populateReplacerData();

        // Set initial section visibility
        this.toggleReplacerSections(this.currentReplacer.type || 'WEIGHTED');
    }

    toggleReplacerSections(type) {
        const weightedSection = document.getElementById('weighted-section');
        const simpleSection = document.getElementById('simple-section');
        if (type === 'WEIGHTED') {
            weightedSection.style.display = 'block';
            simpleSection.style.display = 'none';
        } else if (type === 'SIMPLE') {
            weightedSection.style.display = 'none';
            simpleSection.style.display = 'block';
        }
    }

    addAlternative(treeId = '', chance = 0.1) {
        const container = document.getElementById('alternatives-list');
        if (!container || this.customTrees.length === 0) return;
        const entry = document.createElement('div');
        entry.className = 'alternative-entry';
        entry.style.display = 'flex';
        entry.style.alignItems = 'center';
        entry.style.marginBottom = '5px';
        entry.innerHTML = `
            <select class="tree-select" style="flex: 1;">
                <option value="">Select tree...</option>
                ${this.customTrees.map(tree => `<option value="tree_engine:${tree}" ${treeId === `tree_engine:${tree}` ? 'selected' : ''}>${tree}</option>`).join('')}
            </select>
            <input type="number" class="chance-input" min="0" max="1" step="0.01" value="${chance}" placeholder="Chance" style="width: 60px; margin-left: 5px;">
            <button class="remove-btn" style="margin-left: 5px; width: auto; padding: 5px 8px;">X</button>
        `;
        entry.querySelector('.remove-btn').addEventListener('click', () => {
            entry.remove();
        });
        container.appendChild(entry);
    }

    addSimplePoolEntry(treeId = '') {
        const container = document.getElementById('simple-pool-list');
        if (!container || this.customTrees.length === 0) return;
        const entry = document.createElement('div');
        entry.className = 'simple-pool-entry';
        entry.style.display = 'flex';
        entry.style.alignItems = 'center';
        entry.style.marginBottom = '5px';
        entry.innerHTML = `
            <select class="tree-select" style="flex: 1;">
                <option value="">Select tree...</option>
                ${this.customTrees.map(tree => `<option value="tree_engine:${tree}" ${treeId === `tree_engine:${tree}` ? 'selected' : ''}>${tree}</option>`).join('')}
            </select>
            <button class="remove-btn" style="margin-left: 5px; width: auto; padding: 5px 8px;">X</button>
        `;
        entry.querySelector('.remove-btn').addEventListener('click', () => {
            entry.remove();
        });
        container.appendChild(entry);
    }

    populateSimplePoolData() {
        const container = document.getElementById('simple-pool-list');
        if (!container) return;

        container.innerHTML = '';

        if (this.currentReplacer.type === 'SIMPLE' && this.currentReplacer.features) {
            this.currentReplacer.features.forEach(treeId => {
                this.addSimplePoolEntry(treeId);
            });
        }
    }

    populateReplacerData() {
        const defaultSelect = document.getElementById('default-tree-select');
        const container = document.getElementById('alternatives-list');
        if (!container || !defaultSelect) return;

        container.innerHTML = '';

        // Set default tree directly from JSON
        if (this.currentReplacer.default_tree) {
            defaultSelect.value = this.currentReplacer.default_tree;
        }

        // Add alternatives directly from JSON
        if (this.currentReplacer.alternatives) {
            this.currentReplacer.alternatives.forEach(alt => {
                this.addAlternative(alt.feature, alt.chance);
            });
        }

        // Populate simple pool if type is SIMPLE
        this.populateSimplePoolData();
    }

    async saveReplacer() {
        const vanillaTreeSelect = document.getElementById('replacer-vanilla-tree');
        const typeSelect = document.getElementById('replacer-type-select');
        const type = typeSelect.value;

        if (!this.currentReplacer.id) {
            this.currentReplacer.vanilla_tree_id = vanillaTreeSelect.value;
        }
        if (!this.currentReplacer.vanilla_tree_id) {
            alert('Please select a vanilla tree to replace');
            return;
        }

        // Set type
        this.currentReplacer.type = type;

        if (type === 'WEIGHTED') {
            // Get default tree
            const defaultTree = document.getElementById('default-tree-select').value;
            if (!defaultTree) {
                alert('Please select a default tree');
                return;
            }

            // Set default tree directly
            this.currentReplacer.default_tree = defaultTree;

            // Collect alternatives directly
            const alternatives = [];
            document.querySelectorAll('.alternative-entry').forEach(entry => {
                const feature = entry.querySelector('.tree-select').value;
                if (!feature) return;
                const chance = parseFloat(entry.querySelector('.chance-input').value) || 0;
                if (chance > 0 && chance <= 1) {
                    alternatives.push({ chance: chance, feature: feature });
                }
            });

            // Validate total chance
            const totalChance = alternatives.reduce((sum, alt) => sum + alt.chance, 0);
            if (totalChance > 1.0) {
                alert('Total chance cannot exceed 1.0. Current: ' + totalChance.toFixed(2));
                return;
            }

            // Set alternatives directly
            this.currentReplacer.alternatives = alternatives;

            // Clear simple fields
            delete this.currentReplacer.features;
        } else if (type === 'SIMPLE') {
            // Collect simple pool
            const features = [];
            document.querySelectorAll('.simple-pool-entry').forEach(entry => {
                const feature = entry.querySelector('.tree-select').value;
                if (feature) {
                    features.push(feature);
                }
            });

            if (features.length === 0) {
                alert('Please add at least one tree to the pool');
                return;
            }

            // Set features
            this.currentReplacer.features = features;

            // Clear weighted fields
            delete this.currentReplacer.default_tree;
            delete this.currentReplacer.alternatives;
        }

        // Remove old replacement_pool field if it exists
        delete this.currentReplacer.replacement_pool;

        // Save to backend
        try {
            const response = await fetch('/api/replacers', {
                method: 'POST',
                headers: getAuthHeaders(),
                body: JSON.stringify(this.currentReplacer)
            });

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.error || 'Failed to save replacer');
            }

            // Reload replacers and show list
            await this.loadReplacers();
            this.currentReplacer = null;
            this.renderReplacersPanel();

            // Trigger hot reload
            try {
                await fetch('/api/hot-reload', {
                    method: 'POST',
                    headers: getAuthHeaders()
                });
            } catch (reloadError) {
                console.warn('Hot reload failed, but replacer was saved:', reloadError);
            }

            // Show success message
            const statusDiv = document.getElementById('status');
            if (statusDiv) {
                statusDiv.textContent = 'Tree replacer saved and hot-reloaded!';
                statusDiv.style.background = '#2a5a2a';
                setTimeout(() => {
                    statusDiv.textContent = 'Ready';
                    statusDiv.style.background = '';
                }, 3000);
            }
        } catch (error) {
            console.error('Failed to save replacer:', error);
            alert('Failed to save tree replacer: ' + error.message);
        }
    }

    async deleteReplacer(id) {
        if (!confirm('Are you sure you want to delete this tree replacer? This will restore the vanilla tree.')) {
            return;
        }

        try {
            const response = await fetch(`/api/replacers/${id}`, {
                method: 'DELETE',
                headers: getAuthHeaders()
            });

            if (!response.ok) {
                const error = await response.json();
                throw new Error(error.error || 'Failed to delete replacer');
            }

            // Reload replacers
            await this.loadReplacers();
            this.renderReplacersPanel();

            // Show success message
            const statusDiv = document.getElementById('status');
            if (statusDiv) {
                statusDiv.textContent = 'Tree replacer deleted successfully!';
                statusDiv.style.background = '#2a5a2a';
                setTimeout(() => {
                    statusDiv.textContent = 'Ready';
                    statusDiv.style.background = '';
                }, 3000);
            }
        } catch (error) {
            console.error('Failed to delete replacer:', error);
            alert('Failed to delete tree replacer: ' + error.message);
        }
    }
}

// Initialize when DOM is ready
let treeReplacerUI;
if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', () => {
        treeReplacerUI = new TreeReplacerUI();
    });
} else {
    treeReplacerUI = new TreeReplacerUI();
}
