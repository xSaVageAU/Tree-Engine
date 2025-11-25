package savage.tree_engine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import savage.tree_engine.TreeEngine;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Manages loading, saving, and caching of tree configurations.
 * Now uses TreeWrapper objects that embed native Minecraft tree JSON.
 */
public class TreeConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, TreeWrapper> LOADED_TREES = new HashMap<>();

    /**
     * Load all tree configuration files from the config directory.
     */
    public static void load() {
        LOADED_TREES.clear();
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("tree_engine/trees");
        
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                TreeEngine.LOGGER.info("Created trees config directory");
            }

            File[] files = configDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return;

            for (File file : files) {
                try (FileReader reader = new FileReader(file)) {
                    TreeWrapper wrapper = GSON.fromJson(reader, TreeWrapper.class);
                    String id = file.getName().replace(".json", "");
                    // Ensure ID matches filename
                    if (wrapper.id == null) wrapper.id = id;
                    LOADED_TREES.put(id, wrapper);
                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Failed to load tree config: " + file.getName(), e);
                }
            }

            TreeEngine.LOGGER.info("Loaded " + LOADED_TREES.size() + " tree configurations");

        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to load tree configs", e);
        }
    }

    /**
     * Get all loaded trees.
     */
    public static Map<String, TreeWrapper> getTrees() {
        return LOADED_TREES;
    }
    
    /**
     * Get a specific tree by ID.
     */
    public static TreeWrapper getTree(String id) {
        return LOADED_TREES.get(id);
    }
    
    /**
     * Save a tree configuration to disk.
     */
    public static void saveTree(TreeWrapper tree) {
        if (tree.id == null || tree.id.isEmpty()) {
            throw new IllegalArgumentException("Tree ID cannot be empty");
        }
        
        // Ensure type is set
        if (tree.type == null || tree.type.isEmpty()) {
            tree.type = "minecraft:tree";
        }
        
        LOADED_TREES.put(tree.id, tree);
        
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("tree_engine/trees");
        Path file = configDir.resolve(tree.id + ".json");
        
        try {
            Files.createDirectories(configDir);
            String json = GSON.toJson(tree);
            Files.writeString(file, json);
            TreeEngine.LOGGER.info("Saved tree config: " + tree.id);
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to save tree config: " + tree.id, e);
        }
    }
    
    /**
     * Delete a tree configuration.
     */
    public static boolean deleteTree(String id) {
        if (!LOADED_TREES.containsKey(id)) return false;
        
        LOADED_TREES.remove(id);
        
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("tree_engine/trees");
        Path file = configDir.resolve(id + ".json");
        
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                TreeEngine.LOGGER.info("Deleted tree config: " + id);
            }
            return deleted;
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to delete tree config: " + id, e);
            return false;
        }
    }

    /**
     * Get list of all vanilla Minecraft tree IDs available for import.
     */
    public static java.util.List<String> getVanillaTrees() {
        java.util.List<String> trees = new java.util.ArrayList<>();
        trees.add("acacia");
        trees.add("azalea_tree");
        trees.add("birch");
        trees.add("birch_bees_0002");
        trees.add("birch_bees_002");
        trees.add("birch_bees_005");
        trees.add("cherry");
        trees.add("cherry_bees_005");
        trees.add("dark_oak");
        trees.add("fancy_oak");
        trees.add("fancy_oak_bees");
        trees.add("fancy_oak_bees_0002");
        trees.add("fancy_oak_bees_002");
        trees.add("fancy_oak_bees_005");
        trees.add("jungle_bush");
        trees.add("jungle_tree");
        trees.add("jungle_tree_no_vine");
        trees.add("mangrove");
        trees.add("mega_jungle_tree");
        trees.add("mega_pine");
        trees.add("mega_spruce");
        trees.add("oak");
        trees.add("oak_bees_0002");
        trees.add("oak_bees_002");
        trees.add("oak_bees_005");
        trees.add("pale_oak");
        trees.add("pine");
        trees.add("spruce");
        trees.add("super_birch_bees");
        trees.add("super_birch_bees_0002");
        trees.add("swamp_oak");
        trees.add("tall_mangrove");
        return trees;
    }
    
    /**
     * Helper to convert snake_case to Title Case.
     */
    private static String toTitleCase(String input) {
        StringBuilder titleCase = new StringBuilder(input.length());
        boolean nextTitleCase = true;

        for (char c : input.toCharArray()) {
            if (Character.isSpaceChar(c)) {
                nextTitleCase = true;
            } else if (nextTitleCase) {
                c = Character.toTitleCase(c);
                nextTitleCase = false;
            }

            titleCase.append(c);
        }

        return titleCase.toString();
    }
}
