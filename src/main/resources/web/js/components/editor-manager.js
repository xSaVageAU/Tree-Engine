class EditorManager {
    constructor() {
        this.monacoEditor = null;
        this.isUpdatingEditor = false; // Flag to prevent auto-regeneration during programmatic updates
        this.currentMode = 'TREE'; // 'TREE' or 'PLACEMENT'
        this.initMonacoEditor();
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

                        // Update the appropriate JSON based on current mode
                        if (this.currentMode === 'TREE') {
                            window.currentTreeJson = parsedJson;
                        } else {
                            window.currentPlacedFeatureJson = parsedJson;
                        }
                        // Regenerate tree preview only for TREE mode
                        if (this.currentMode === 'TREE' && typeof generateTree === 'function') {
                            generateTree();
                        }
                    } catch (e) {
                        // Invalid JSON, don't regenerate
                        console.log("Invalid JSON, skipping regeneration:", e.message);
                    }
                }, 500);
            });
            // Setup tab switching
            document.querySelectorAll('.tab-btn').forEach(btn => {
                btn.addEventListener('click', () => {
                    const tab = btn.dataset.tab;
                    this.switchTab(tab === 'tree' ? 'TREE' : 'PLACEMENT');
                });
            });
        });
    }

    openJsonEditor() {
        const bottomPanel = document.getElementById('bottom-panel');
        bottomPanel.classList.add('open');
        // Reset to Tree tab
        this.currentMode = 'TREE';
        document.querySelectorAll('.tab-btn').forEach(btn => {
            if (btn.dataset.tab === 'tree') {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });
        // Ensure Monaco editor has the current tree JSON
        if (this.monacoEditor && window.currentTreeJson) {
            this.isUpdatingEditor = true;
            this.monacoEditor.setValue(JSON.stringify(window.currentTreeJson, null, 2));
            setTimeout(() => {
                this.isUpdatingEditor = false;
            }, 100);
        }
    }

    closeJsonEditor() {
        const bottomPanel = document.getElementById('bottom-panel');
        bottomPanel.classList.remove('open');
        // Sync changes from Monaco back to the appropriate variable
        if (this.monacoEditor) {
            try {
                const jsonText = this.monacoEditor.getValue();
                const parsedJson = JSON.parse(jsonText);
                if (this.currentMode === 'TREE') {
                    window.currentTreeJson = parsedJson;
                } else {
                    window.currentPlacedFeatureJson = parsedJson;
                }
            } catch (e) {
                console.error("Invalid JSON in Monaco editor:", e);
                alert("Invalid JSON in editor. Please fix errors before closing.");
                bottomPanel.classList.add('open');
            }
        }
    }

    switchTab(mode) {
        if (this.currentMode === mode) return;
        // Save current editor content
        if (this.monacoEditor) {
            try {
                const jsonText = this.monacoEditor.getValue();
                const parsedJson = JSON.parse(jsonText);
                if (this.currentMode === 'TREE') {
                    window.currentTreeJson = parsedJson;
                } else {
                    window.currentPlacedFeatureJson = parsedJson;
                }
            } catch (e) {
                console.error('Invalid JSON, cannot switch tabs');
                return;
            }
        }
        // Switch mode
        this.currentMode = mode;
        // Update tab UI
        document.querySelectorAll('.tab-btn').forEach(btn => {
            if ((mode === 'TREE' && btn.dataset.tab === 'tree') ||
                (mode === 'PLACEMENT' && btn.dataset.tab === 'placement')) {
                btn.classList.add('active');
            } else {
                btn.classList.remove('active');
            }
        });
        // Load new content
        this.isUpdatingEditor = true;
        const jsonToLoad = mode === 'TREE' ? window.currentTreeJson : window.currentPlacedFeatureJson;
        if (this.monacoEditor) {
            if (jsonToLoad) {
                this.monacoEditor.setValue(JSON.stringify(jsonToLoad, null, 2));
            } else {
                this.monacoEditor.setValue('{}');
            }
        }
        setTimeout(() => {
            this.isUpdatingEditor = false;
        }, 100);
    }
}