// Combined TreeBrowser that uses TreeManager and EditorManager

window.addEventListener('DOMContentLoaded', () => {
    const treeManager = new TreeManager();
    const editorManager = new EditorManager();

    // Expose to global scope
    window.treeManager = treeManager;
    window.editorManager = editorManager;

    // Bind buttons
    document.getElementById('btn-save')?.addEventListener('click', () => treeManager.saveCurrentTree());
    document.getElementById('btn-delete')?.addEventListener('click', () => treeManager.deleteSelected());
    document.getElementById('btn-create-new')?.addEventListener('click', () => treeManager.createNewTree());
    document.getElementById('btn-import')?.addEventListener('click', () => treeManager.openImportModal());
    document.getElementById('btn-tree-replacers')?.addEventListener('click', () => treeManager.showReplacers());
    document.getElementById('btn-edit-json')?.addEventListener('click', () => editorManager.openJsonEditor());
    document.getElementById('btn-close-editor')?.addEventListener('click', () => editorManager.closeJsonEditor());
    document.getElementById('btn-back-to-library')?.addEventListener('click', () => treeManager.hideSettings());
    document.getElementById('btn-back-to-library-from-replacers')?.addEventListener('click', () => treeManager.hideReplacers());
});