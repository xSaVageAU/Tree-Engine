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

public class TreeConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Map<String, TreeDefinition> LOADED_TREES = new HashMap<>();

    public static void load() {
        LOADED_TREES.clear();
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("tree_engine/trees");
        
        try {
            if (!Files.exists(configDir)) {
                Files.createDirectories(configDir);
                // Create a default example file if empty
                createExample(configDir);
            }

            File[] files = configDir.toFile().listFiles((dir, name) -> name.endsWith(".json"));
            if (files == null) return;

            for (File file : files) {
                try (FileReader reader = new FileReader(file)) {
                    TreeDefinition def = GSON.fromJson(reader, TreeDefinition.class);
                    String id = file.getName().replace(".json", "");
                    // Ensure ID matches filename
                    def.id = id;
                    LOADED_TREES.put(id, def);
                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Failed to load tree config: " + file.getName(), e);
                }
            }

            resolveInheritance();

        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to load tree configs", e);
        }
    }

    private static void createExample(Path dir) throws IOException {
        TreeDefinition base = new TreeDefinition();
        base.id = "base_oak";
        base.name = "Base Oak";
        base.description = "Standard oak tree template";
        base.trunk_block = "minecraft:oak_log";
        base.foliage_block = "minecraft:oak_leaves";
        base.trunk_height_min = 4;
        base.trunk_height_max = 6;
        base.foliage_radius = 2;
        base.foliage_offset = 0;
        
        String json = GSON.toJson(base);
        Files.writeString(dir.resolve("base_oak.json"), json);
    }

    private static void resolveInheritance() {
        for (Map.Entry<String, TreeDefinition> entry : LOADED_TREES.entrySet()) {
            resolve(entry.getValue(), 0);
        }
    }

    private static void resolve(TreeDefinition def, int depth) {
        if (depth > 10) {
            TreeEngine.LOGGER.error("Circular or too deep inheritance detected for tree: " + def.id);
            return;
        }
        if (def.parent != null && !def.parent.isEmpty()) {
            TreeDefinition parentDef = LOADED_TREES.get(def.parent);
            if (parentDef != null) {
                resolve(parentDef, depth + 1);
                def.merge(parentDef);
            } else {
                TreeEngine.LOGGER.warn("Parent tree not found: " + def.parent);
            }
        }
    }

    public static Map<String, TreeDefinition> getTrees() {
        return LOADED_TREES;
    }
    
    public static TreeDefinition getTree(String id) {
        return LOADED_TREES.get(id);
    }
    
    public static void saveTree(TreeDefinition tree) {
        if (tree.id == null || tree.id.isEmpty()) {
            throw new IllegalArgumentException("Tree ID cannot be empty");
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
    
    public static boolean deleteTree(String id) {
        if (!LOADED_TREES.containsKey(id)) return false;
        
        LOADED_TREES.remove(id);
        
        Path configDir = FabricLoader.getInstance().getConfigDir().resolve("tree_engine/trees");
        Path file = configDir.resolve(id + ".json");
        
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to delete tree config: " + id, e);
            return false;
        }
    }
}
