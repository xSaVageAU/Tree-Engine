package savage.tree_engine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import savage.tree_engine.TreeEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Manages tree replacer configurations.
 * A tree replacer allows replacing vanilla tree models with custom tree pools.
 * 
 * Refactored to use the existence of datapack files as the source of truth.
 */
public class TreeReplacerManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATAPACK_ROOT = Paths.get("config", "tree_engine", "datapacks", "tree_engine_trees");
    
    public static class TreeReplacer {
        public String id;
        public String vanilla_tree_id;
        public List<String> replacement_pool;
        
        public TreeReplacer() {
            this.replacement_pool = new ArrayList<>();
        }
        
        public TreeReplacer(String id, String vanillaTreeId, List<String> replacementPool) {
            this.id = id;
            this.vanilla_tree_id = vanillaTreeId;
            this.replacement_pool = replacementPool != null ? replacementPool : new ArrayList<>();
        }
    }
    
    /**
     * Initialize the tree replacer system.
     * Now primarily ensures that PlacedFeatures exist for custom trees.
     */
    public static void init() {
        // Ensure all custom trees have corresponding PlacedFeatures
        ensurePlacedFeaturesExist();
    }
    
    /**
     * Scans all custom trees and ensures they have a corresponding PlacedFeature.
     * This is required for them to be used in simple_random_selector.
     */
    private static void ensurePlacedFeaturesExist() {
        try {
            Path configuredFeatureDir = DATAPACK_ROOT.resolve("data").resolve("tree_engine").resolve("worldgen").resolve("configured_feature");
            Path placedFeatureDir = DATAPACK_ROOT.resolve("data").resolve("tree_engine").resolve("worldgen").resolve("placed_feature");
            
            if (!Files.exists(configuredFeatureDir)) return;
            Files.createDirectories(placedFeatureDir);
            
            try (Stream<Path> stream = Files.list(configuredFeatureDir)) {
                stream.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".json"))
                    .forEach(configFile -> {
                        String filename = configFile.getFileName().toString();
                        Path placedFile = placedFeatureDir.resolve(filename);
                        
                        if (!Files.exists(placedFile)) {
                            try {
                                String id = filename.replace(".json", "");
                                JsonObject placedFeature = new JsonObject();
                                placedFeature.addProperty("feature", "tree_engine:" + id);
                                placedFeature.add("placement", new com.google.gson.JsonArray()); // Empty placement rules
                                
                                Files.writeString(placedFile, GSON.toJson(placedFeature));
                                TreeEngine.LOGGER.info("Auto-generated missing PlacedFeature for: " + id);
                            } catch (IOException e) {
                                TreeEngine.LOGGER.error("Failed to generate PlacedFeature for " + filename, e);
                            }
                        }
                    });
            }
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to ensure PlacedFeatures exist", e);
        }
    }
    
    /**
     * Get all tree replacers by scanning the datapack directory.
     */
    public static List<TreeReplacer> getAll() {
        List<TreeReplacer> replacers = new ArrayList<>();
        
        // Scan data/minecraft/worldgen/configured_feature/
        Path minecraftFeaturesDir = DATAPACK_ROOT.resolve("data").resolve("minecraft").resolve("worldgen").resolve("configured_feature");
        
        if (!Files.exists(minecraftFeaturesDir)) {
            return replacers;
        }
        
        try (Stream<Path> stream = Files.list(minecraftFeaturesDir)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(file -> {
                    try {
                        TreeReplacer replacer = parseReplacerFile(file);
                        if (replacer != null) {
                            replacers.add(replacer);
                        }
                    } catch (Exception e) {
                        TreeEngine.LOGGER.error("Failed to parse replacer file: " + file, e);
                    }
                });
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to scan for tree replacers", e);
        }
        
        return replacers;
    }
    
    /**
     * Parse a configured feature file to reconstruct a TreeReplacer object.
     */
    private static TreeReplacer parseReplacerFile(Path file) throws IOException {
        String jsonContent = Files.readString(file);
        JsonObject json = JsonParser.parseString(jsonContent).getAsJsonObject();
        
        // Check if it's a simple_random_selector (which we use for replacers)
        if (!json.has("type") || !json.get("type").getAsString().equals("minecraft:simple_random_selector")) {
            return null;
        }
        
        if (!json.has("config")) return null;
        JsonObject config = json.getAsJsonObject("config");
        
        if (!config.has("features")) return null;
        JsonArray features = config.getAsJsonArray("features");
        
        List<String> pool = new ArrayList<>();
        for (JsonElement element : features) {
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                pool.add(element.getAsString());
            } else if (element.isJsonObject()) {
                JsonObject entry = element.getAsJsonObject();
                if (entry.has("feature")) {
                    pool.add(entry.get("feature").getAsString());
                }
            }
        }
        
        String filename = file.getFileName().toString();
        String vanillaId = "minecraft:" + filename.replace(".json", "");
        
        // We use the vanilla ID as the replacer ID since it's 1:1
        return new TreeReplacer(vanillaId, vanillaId, pool);
    }
    
    /**
     * Get a tree replacer by ID.
     */
    public static TreeReplacer get(String id) {
        // ID is expected to be the vanilla tree ID (e.g., "minecraft:oak")
        // or potentially just "oak" if passed from frontend
        
        String pathStr = id;
        if (id.contains(":")) {
            String[] parts = id.split(":");
            if (parts.length == 2 && parts[0].equals("minecraft")) {
                pathStr = parts[1];
            }
        }
        
        Path file = DATAPACK_ROOT.resolve("data").resolve("minecraft").resolve("worldgen").resolve("configured_feature").resolve(pathStr + ".json");
        
        if (Files.exists(file)) {
            try {
                return parseReplacerFile(file);
            } catch (IOException e) {
                TreeEngine.LOGGER.error("Failed to get replacer: " + id, e);
            }
        }
        
        return null;
    }
    
    /**
     * Save or update a tree replacer.
     * Directly writes the datapack file.
     */
    public static void saveReplacer(TreeReplacer replacer) throws IOException {
        // Generate datapack file
        generateDatapackFile(replacer);
    }
    
    /**
     * Delete a tree replacer.
     * Directly deletes the datapack file.
     */
    public static void delete(String id) throws IOException {
        // ID is expected to be the vanilla tree ID
        deleteDatapackFile(id);
    }
    
    /**
     * Generate a datapack file for a tree replacer.
     * This creates a file in the minecraft namespace to override the vanilla tree.
     */
    private static void generateDatapackFile(TreeReplacer replacer) throws IOException {
        // Parse the vanilla tree ID (e.g., "minecraft:oak" -> namespace="minecraft", path="oak")
        String vanillaId = replacer.vanilla_tree_id;
        String[] parts = vanillaId.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid vanilla tree ID: " + vanillaId);
        }
        
        String namespace = parts[0];
        String path = parts[1];
        
        // Create the output path: data/{namespace}/worldgen/configured_feature/{path}.json
        Path outputDir = DATAPACK_ROOT.resolve("data")
            .resolve(namespace)
            .resolve("worldgen")
            .resolve("configured_feature");
        Files.createDirectories(outputDir);
        
        Path outputFile = outputDir.resolve(path + ".json");
        
        // Generate the configured feature JSON
        JsonObject feature = new JsonObject();
        
        if (replacer.replacement_pool.isEmpty()) {
            throw new IllegalArgumentException("Replacement pool cannot be empty");
        }

        // Always use simple_random_selector, even for a single tree
        feature.addProperty("type", "minecraft:simple_random_selector");
        
        JsonObject configObj = new JsonObject();
        JsonArray featuresArray = new JsonArray();
        
        // For simple_random_selector, we just provide a list of PlacedFeatures (strings)
        // It picks one with equal probability
        for (String treeId : replacer.replacement_pool) {
            featuresArray.add(treeId);
        }
        
        configObj.add("features", featuresArray);
        feature.add("config", configObj);
        
        // Write the file
        String json = GSON.toJson(feature);
        Files.writeString(outputFile, json);
        
        TreeEngine.LOGGER.info("Generated tree replacer datapack file: " + outputFile);
    }
    
    /**
     * Delete a datapack file for a vanilla tree.
     */
    private static void deleteDatapackFile(String vanillaTreeId) throws IOException {
        String[] parts = vanillaTreeId.split(":", 2);
        if (parts.length != 2) {
            // Try treating as path if no colon
            parts = new String[]{"minecraft", vanillaTreeId};
        }
        
        String namespace = parts[0];
        String path = parts[1];
        
        Path outputFile = DATAPACK_ROOT.resolve("data")
            .resolve(namespace)
            .resolve("worldgen")
            .resolve("configured_feature")
            .resolve(path + ".json");
        
        if (Files.exists(outputFile)) {
            Files.delete(outputFile);
            TreeEngine.LOGGER.info("Deleted tree replacer datapack file: " + outputFile);
        }
    }
}
