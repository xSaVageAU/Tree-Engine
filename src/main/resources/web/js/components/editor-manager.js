class EditorManager {
    constructor() {
        this.monacoEditor = null;
        this.isUpdatingEditor = false; // Flag to prevent auto-regeneration during programmatic updates
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
}