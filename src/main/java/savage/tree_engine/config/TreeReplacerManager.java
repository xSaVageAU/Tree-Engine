package savage.tree_engine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import savage.tree_engine.TreeEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Manages tree replacer configurations.
 * A tree replacer allows replacing vanilla tree models with custom tree pools.
 */
public class TreeReplacerManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_FILE = Paths.get("config", "tree_engine", "tree_replacers.json");
    private static final Path DATAPACK_ROOT = Paths.get("config", "tree_engine", "datapacks", "tree_engine_trees");
    
    private static TreeReplacerConfig config;
    
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
    
    public static class TreeReplacerConfig {
        public List<TreeReplacer> replacers;
        
        public TreeReplacerConfig() {
            this.replacers = new ArrayList<>();
        }
    }
    
    /**
     * Initialize the tree replacer system.
     */
    public static void init() {
        try {
            if (Files.exists(CONFIG_FILE)) {
                String json = Files.readString(CONFIG_FILE);
                config = GSON.fromJson(json, TreeReplacerConfig.class);
                TreeEngine.LOGGER.info("Loaded tree replacers config");
            } else {
                config = new TreeReplacerConfig();
                save();
                TreeEngine.LOGGER.info("Created default tree replacers config");
            }
            
            // Generate datapack files for all replacers
            regenerateAll();
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to initialize tree replacers", e);
            config = new TreeReplacerConfig();
        }
    }
    
    /**
     * Get all tree replacers.
     */
    public static List<TreeReplacer> getAll() {
        if (config == null) {
            init();
        }
        return config.replacers;
    }
    
    /**
     * Get a tree replacer by ID.
     */
    public static TreeReplacer get(String id) {
        if (config == null) {
            init();
        }
        return config.replacers.stream()
            .filter(r -> r.id.equals(id))
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Save or update a tree replacer.
     */
    public static void saveReplacer(TreeReplacer replacer) throws IOException {
        if (config == null) {
            init();
        }
        
        // Remove existing replacer with same ID
        config.replacers.removeIf(r -> r.id.equals(replacer.id));
        
        // Add new replacer
        config.replacers.add(replacer);
        
        // Save config
        save();
        
        // Generate datapack file
        generateDatapackFile(replacer);
    }
    
    /**
     * Delete a tree replacer.
     */
    public static void delete(String id) throws IOException {
        if (config == null) {
            init();
        }
        
        TreeReplacer replacer = get(id);
        if (replacer != null) {
            // Remove from config
            config.replacers.removeIf(r -> r.id.equals(id));
            save();
            
            // Delete datapack file
            deleteDatapackFile(replacer.vanilla_tree_id);
        }
    }
    
    /**
     * Save the configuration file.
     */
    private static void save() throws IOException {
        Files.createDirectories(CONFIG_FILE.getParent());
        String json = GSON.toJson(config);
        Files.writeString(CONFIG_FILE, json);
    }
    
    /**
     * Regenerate all datapack files.
     */
    public static void regenerateAll() throws IOException {
        if (config == null) {
            init();
            return;
        }
        
        for (TreeReplacer replacer : config.replacers) {
            generateDatapackFile(replacer);
        }
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
        
        if (replacer.replacement_pool.size() == 1) {
            // If only one tree in the pool, reference it directly
            feature.addProperty("type", "minecraft:tree");
            // Actually, we should just reference the custom feature directly
            // We'll use a reference instead
            feature.addProperty("type", "minecraft:reference");
            feature.addProperty("feature", replacer.replacement_pool.get(0));
        } else if (replacer.replacement_pool.size() > 1) {
            // If multiple trees, use simple_random_selector
            feature.addProperty("type", "minecraft:simple_random_selector");
            
            JsonObject configObj = new JsonObject();
            JsonArray featuresArray = new JsonArray();
            
            // Equal chance for each tree in the pool
            double chance = 1.0 / replacer.replacement_pool.size();
            
            for (String treeId : replacer.replacement_pool) {
                JsonObject featureEntry = new JsonObject();
                featureEntry.addProperty("feature", treeId);
                featureEntry.addProperty("chance", chance);
                featuresArray.add(featureEntry);
            }
            
            configObj.add("features", featuresArray);
            feature.add("config", configObj);
        } else {
            // Empty pool - this shouldn't happen, but handle it gracefully
            throw new IllegalArgumentException("Replacement pool cannot be empty");
        }
        
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
            return;
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
