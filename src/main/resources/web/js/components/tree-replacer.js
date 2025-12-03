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
        return `
            <div class="replacer-item" style="background: #1a1a1a; padding: 15px; margin-bottom: 10px; border-radius: 5px; border: 1px solid #333;">
                <div style="margin-bottom: 10px;">
                    <h4 style="margin: 0 0 5px 0; color: #fff;">${this.getTreeDisplayName(replacer.vanilla_tree_id)}</h4>
                    <p style="margin: 0; color: #858585; font-size: 12px;">${replacer.vanilla_tree_id}</p>
                </div>
                <div style="display: flex; justify-content: center; gap: 5px; margin-bottom: 10px;">
                    <button id="edit-replacer-${replacer.id}" class="secondary" style="padding: 5px 10px; font-size: 12px;">Edit</button>
                    <button id="delete-replacer-${replacer.id}" class="secondary" style="padding: 5px 10px; font-size: 12px;">Delete</button>
                </div>
                <div style="color: #aaa; font-size: 13px;">
                    <strong>Replacement Pool (${replacer.replacement_pool.length}):</strong><br>
                    ${replacer.replacement_pool.map(entry => {
            const id = typeof entry === 'string' ? entry : entry.tree_id;
            const weight = typeof entry === 'string' ? '' : ` (${entry.weight})`;
            return `<span style="display: inline-block; background: #2a2a2a; padding: 2px 8px; margin: 2px; border-radius: 3px; font-size: 11px;">${id}${weight}</span>`;
        }).join('')}
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
            replacement_pool: []
        };
        this.renderReplacerForm();
    }

    editReplacer(replacer) {
        this.currentReplacer = JSON.parse(JSON.stringify(replacer)); // Deep copy
        this.renderReplacerForm();
    }

    renderReplacerForm() {
        const panel = document.getElementById('replacers-panel');
        const scrollContent = panel.querySelector('.scroll-content');

        scrollContent.innerHTML = `
            <div style="padding: 15px;">
                <h3 style="margin-top: 0;">${this.currentReplacer.id ? 'Edit' : 'Create'} Tree Replacer</h3>
                
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

                <div class="control-group">
                    <label>Replacement Pool</label>
                    <p style="color: #858585; font-size: 12px; margin: 0 0 10px 0;">Select custom trees to randomly replace the vanilla tree</p>

                    <div id="replacement-pool-entries" style="max-height: 200px; overflow-y: auto; border: 1px solid #333; border-radius: 3px; background: #1a1a1a; padding: 10px;">
                        ${this.customTrees.length === 0
                ? '<p style="color: #858585; text-align: center; margin: 0;">No custom trees available. Create some trees first!</p>'
                : ''
            }
                    </div>
                    <button id="btn-add-pool-entry" style="width: 100%; margin-top: 10px;">+ Add Tree to Pool</button>
                    <p style="font-size: 0.9em; color: #888; margin-top: 5px;">
                        💡 Tip: Higher weights = more common. Example: weight 70 = 70%, weight 20 = 20%, weight 10 = 10%
                    </p>
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
        document.getElementById('btn-add-pool-entry').addEventListener('click', () => this.addPoolEntry());

        // Populate existing entries
        this.populatePoolEntries();
    }

    addPoolEntry(treeId = '', weight = 1) {
        const container = document.getElementById('replacement-pool-entries');
        if (!container || this.customTrees.length === 0) return;
        const entry = document.createElement('div');
        entry.className = 'pool-entry';
        entry.style.display = 'flex';
        entry.style.alignItems = 'center';
        entry.style.marginBottom = '5px';
        entry.innerHTML = `
            <select class="tree-select" style="flex: 1;">
                <option value="">Select tree...</option>
                ${this.customTrees.map(tree => `<option value="tree_engine:${tree}" ${treeId === `tree_engine:${tree}` ? 'selected' : ''}>${tree}</option>`).join('')}
            </select>
            <input type="number" class="weight-input" min="1" value="${weight}" placeholder="Weight" style="width: 80px; margin-left: 10px;">
            <button class="remove-btn" style="margin-left: 10px;">Remove</button>
        `;
        entry.querySelector('.remove-btn').addEventListener('click', () => {
            entry.remove();
        });
        container.appendChild(entry);
    }

    populatePoolEntries() {
        const container = document.getElementById('replacement-pool-entries');
        if (!container) return;
        container.innerHTML = '';
        if (!this.currentReplacer.replacement_pool) return;
        this.currentReplacer.replacement_pool.forEach(entry => {
            if (typeof entry === 'string') {
                this.addPoolEntry(entry, 1);
            } else {
                this.addPoolEntry(entry.tree_id, entry.weight);
            }
        });
    }

    async saveReplacer() {
        const vanillaTreeSelect = document.getElementById('replacer-vanilla-tree');
        if (!this.currentReplacer.id) {
            this.currentReplacer.vanilla_tree_id = vanillaTreeSelect.value;
        }

        // Validate
        if (!this.currentReplacer.vanilla_tree_id) {
            alert('Please select a vanilla tree to replace');
            return;
        }

        // Collect pool data
        const pool = [];
        document.querySelectorAll('.pool-entry').forEach(entry => {
            const treeId = entry.querySelector('.tree-select').value;
            if (!treeId) return; // skip empty selects
            const weight = parseInt(entry.querySelector('.weight-input').value) || 1;
            pool.push({ tree_id: treeId, weight: weight });
        });
        this.currentReplacer.replacement_pool = pool;

        if (pool.length === 0) {
            alert('Please add at least one tree to the replacement pool');
            return;
        }

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
