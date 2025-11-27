package savage.tree_engine.web;

import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.MainConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

public class TextureHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        // Path format: /textures/{packName}/{filename}
        String[] parts = path.split("/");
        
        if (parts.length < 4) {
            send404(exchange);
            return;
        }
        
        String packName = parts[2];
        String filename = parts[3];
        
        // Try to load texture
        byte[] textureData = loadTexture(packName, filename);
        
        if (textureData != null) {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, textureData.length);
            OutputStream os = exchange.getResponseBody();
            os.write(textureData);
            os.close();
        } else {
            send404(exchange);
        }
    }
    
    private byte[] loadTexture(String packName, String filename) {
        Path texturesDir = MainConfig.getTexturesDir();
        
        TreeEngine.LOGGER.info("Loading texture: pack='{}', filename='{}'", packName, filename);
        TreeEngine.LOGGER.info("Textures directory: {}", texturesDir.toAbsolutePath());
        
        // Try multiple possible paths
        String[] possiblePaths = {
            "assets/minecraft/textures/block/" + filename,  // Standard resource pack
            "assets/minecraft/textures/blocks/" + filename, // Alternative
            filename,  // Direct file
            "textures/block/" + filename,  // Without assets/minecraft
            "block/" + filename  // Just block folder
        };
        
        // Try folder first
        Path folderPath = texturesDir.resolve(packName);
        if (Files.isDirectory(folderPath)) {
            TreeEngine.LOGGER.info("Trying folder: {}", folderPath.toAbsolutePath());
            for (String texturePath : possiblePaths) {
                Path textureFile = folderPath.resolve(texturePath);
                if (Files.exists(textureFile)) {
                    TreeEngine.LOGGER.info("Found texture at: {}", textureFile);
                    try {
                        return Files.readAllBytes(textureFile);
                    } catch (IOException e) {
                        TreeEngine.LOGGER.error("Failed to read texture from folder", e);
                    }
                }
            }
            TreeEngine.LOGGER.warn("Texture not found in folder, tried paths: {}", String.join(", ", possiblePaths));
        }
        
        // Try zip file
        Path zipPath = texturesDir.resolve(packName + ".zip");
        if (Files.exists(zipPath)) {
            TreeEngine.LOGGER.info("Trying zip file: {}", zipPath.toAbsolutePath());
            try (ZipFile zipFile = new ZipFile(zipPath.toFile())) {
                for (String texturePath : possiblePaths) {
                    ZipEntry entry = zipFile.getEntry(texturePath);
                    if (entry != null) {
                        TreeEngine.LOGGER.info("Found texture in zip at: {}", texturePath);
                        try (InputStream is = zipFile.getInputStream(entry)) {
                            return is.readAllBytes();
                        }
                    }
                }
                TreeEngine.LOGGER.warn("Texture not found in zip, tried paths: {}", String.join(", ", possiblePaths));
            } catch (IOException e) {
                TreeEngine.LOGGER.error("Failed to read texture from zip", e);
            }
        } else {
            TreeEngine.LOGGER.warn("Zip file not found: {}", zipPath.toAbsolutePath());
        }
        
        TreeEngine.LOGGER.error("Texture not found: pack='{}', filename='{}'", packName, filename);
        return null;
    }
    
    private String getMinecraftTexturePath(String filename) {
        // Convert our simple filename to Minecraft resource pack path
        // oak_log.png -> assets/minecraft/textures/block/oak_log.png
        String name = filename.replace(".png", "");
        return "assets/minecraft/textures/block/" + name + ".png";
    }
    
    private void send404(HttpExchange exchange) throws IOException {
        String error = "Texture not found";
        exchange.sendResponseHeaders(404, error.length());
        OutputStream os = exchange.getResponseBody();
        os.write(error.getBytes());
        os.close();
    }
}
