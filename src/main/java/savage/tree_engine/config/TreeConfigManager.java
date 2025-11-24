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
        // Simple multi-pass resolution or recursive
        // Let's do a simple recursive resolve for each tree
        for (Map.Entry<String, TreeDefinition> entry : LOADED_TREES.entrySet()) {
            resolve(entry.getValue(), 0);
        }
    }

    private static void resolve(TreeDefinition def, int depth) {
        if (depth > 10) {
            TreeEngine.LOGGER.error("Circular or too deep inheritance detected!");
            return;
        }
        if (def.parent != null && !def.parent.isEmpty()) {
            TreeDefinition parentDef = LOADED_TREES.get(def.parent);
            if (parentDef != null) {
                // Ensure parent is resolved first
                resolve(parentDef, depth + 1);
                // Merge parent into child
                def.merge(parentDef);
            } else {
                TreeEngine.LOGGER.warn("Parent tree not found: " + def.parent);
            }
        }
    }

    public static Map<String, TreeDefinition> getTrees() {
        return LOADED_TREES;
    }
}
