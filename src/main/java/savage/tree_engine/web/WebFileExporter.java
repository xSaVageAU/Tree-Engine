package savage.tree_engine.web;

import savage.tree_engine.TreeEngine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/**
 * Handles exporting web files from the mod's resources to the config directory.
 * This allows the web editor files to be served by the HTTP server.
 */
public class WebFileExporter {
    
    /**
     * List of all web files to export from resources/web to the config web directory.
     * Add new files here when expanding the web interface.
     */
    private static final String[] WEB_FILES = {
        "index.html",
        "css/main.css",
        "js/main.js",
        "js/config.js",
        "js/components/editor-manager.js",
        "js/components/tree-browser.js",
        "js/components/tree-manager.js",
        "js/components/tree-replacer.js",
        "js/components/ui.js",
        "js/services/api.js",
        "js/services/rendering.js",
        "js/services/rendering/color-resolver.js",
        "js/services/rendering/log-renderer.js",
        "js/services/rendering/leaf-renderer.js",
        "js/services/rendering/block-renderer.js",
        "js/services/rendering/ground-renderer.js",
        "js/utils/helpers.js",
        "forms/schema-form.js"
    };
    
    /**
     * Exports all web files from the mod's resources to the config directory.
     * Creates necessary subdirectories and logs the export process.
     * 
     * @param webDir The target directory to export files to
     */
    public static void exportFiles(Path webDir) {
        try {
            java.nio.file.Files.createDirectories(webDir);
            
            int successCount = 0;
            int failCount = 0;
            
            for (String filePath : WEB_FILES) {
                if (exportFile(filePath, webDir)) {
                    successCount++;
                } else {
                    failCount++;
                }
            }
            
            TreeEngine.LOGGER.info("Exported {} web files to: {}", successCount, webDir.toAbsolutePath());
            if (failCount > 0) {
                TreeEngine.LOGGER.warn("{} files failed to export", failCount);
            }
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to create web directory", e);
        }
    }
    
    /**
     * Exports a single file from resources to the target directory.
     * 
     * @param filePath The relative path of the file (e.g., "js/main.js")
     * @param webDir The target directory
     * @return true if the file was exported successfully, false otherwise
     */
    private static boolean exportFile(String filePath, Path webDir) {
        try (InputStream is = WebFileExporter.class.getClassLoader().getResourceAsStream("web/" + filePath)) {
            if (is != null) {
                Path target = webDir.resolve(filePath);
                java.nio.file.Files.createDirectories(target.getParent());
                java.nio.file.Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                TreeEngine.LOGGER.debug("Exported: {}", filePath);
                return true;
            } else {
                TreeEngine.LOGGER.warn("File not found in resources: web/{}", filePath);
                return false;
            }
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to export file: {}", filePath, e);
            return false;
        }
    }
}
