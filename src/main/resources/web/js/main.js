// Main entry point for the application

window.addEventListener('DOMContentLoaded', () => {
    try {
        initBabylon();
    } catch (e) {
        console.error("Failed to initialize 3D renderer:", e);
        document.getElementById('status').textContent = "Renderer failed (check console)";
    }
    setupUI();
    // Initial load handled by tree browser
});