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

        // Load into editor
        document.getElementById('tree_name').value = tree.name || "";
        document.getElementById('trunk_block').value = tree.trunk_block || "minecraft:oak_log";
        document.getElementById('foliage_block').value = tree.foliage_block || "minecraft:oak_leaves";
        document.getElementById('trunk_height_min').value = tree.trunk_height_min || 4;
        document.getElementById('trunk_height_max').value = tree.trunk_height_max || 6;
        document.getElementById('foliage_radius').value = tree.foliage_radius || 2;
        document.getElementById('foliage_offset').value = tree.foliage_offset || 0;

        // Update UI displays
        document.getElementById('height_min_val').textContent = tree.trunk_height_min || 4;
        document.getElementById('height_max_val').textContent = tree.trunk_height_max || 6;
        document.getElementById('radius_val').textContent = tree.foliage_radius || 2;
        document.getElementById('offset_val').textContent = tree.foliage_offset || 0;

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

        // Update displays
        document.getElementById('height_min_val').textContent = 4;
        document.getElementById('height_max_val').textContent = 6;
        document.getElementById('radius_val').textContent = 2;
        document.getElementById('offset_val').textContent = 0;

        this.updateDeleteButtonState();

        switchTab('editor');
        updateMaterials();
        generateTree();
    }

    async saveCurrentTree() {
        const name = document.getElementById('tree_name').value;
        if (!name) {
            alert("Please enter a tree name.");
            return;
        }

        const tree = {
            id: this.selectedTreeId, // If null, backend generates new ID based on name
            name: name,
            description: "Created via Web Editor",
            trunk_block: document.getElementById('trunk_block').value,
            foliage_block: document.getElementById('foliage_block').value,
            trunk_height_min: parseInt(document.getElementById('trunk_height_min').value),
            trunk_height_max: parseInt(document.getElementById('trunk_height_max').value),
            foliage_radius: parseInt(document.getElementById('foliage_radius').value),
            foliage_offset: parseInt(document.getElementById('foliage_offset').value)
        };

        try {
            const response = await fetch('/api/trees', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(tree)
            });

            if (response.ok) {
                const savedTree = await response.json();
                this.selectedTreeId = savedTree.id; // Update ID in case it was new
                await this.loadTrees();
                this.renderTreeList();
                this.updateDeleteButtonState();

                // Optional: Show toast instead of alert
                const status = document.getElementById('status');
                const originalText = status.textContent;
                status.textContent = "Tree Saved!";
                status.style.color = "#5ca363";
                setTimeout(() => {
                    status.textContent = originalText;
                    status.style.color = "";
                }, 2000);
            } else {
                alert("Failed to save tree");
            }
        } catch (e) {
            console.error(e);
            alert("Error saving tree");
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
}

// Initialize
let treeBrowser;
window.addEventListener('DOMContentLoaded', () => {
    treeBrowser = new TreeBrowser();

    // Bind buttons
    document.getElementById('btn-save')?.addEventListener('click', () => treeBrowser.saveCurrentTree());
    document.getElementById('btn-delete')?.addEventListener('click', () => treeBrowser.deleteSelected());
    document.getElementById('btn-create-new')?.addEventListener('click', () => treeBrowser.createNewTree());
});
