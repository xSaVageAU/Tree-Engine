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

    public static java.util.List<String> getVanillaTrees() {
        // Complete list of vanilla Minecraft tree configured features
        java.util.List<String> trees = new java.util.ArrayList<>();
        trees.add("minecraft:acacia");
        trees.add("minecraft:azalea_tree");
        trees.add("minecraft:birch_bees_0002");
        trees.add("minecraft:birch_bees_002");
        trees.add("minecraft:birch_bees_005");
        trees.add("minecraft:birch");
        trees.add("minecraft:cherry_bees_005");
        trees.add("minecraft:cherry");
        trees.add("minecraft:dark_oak");
        trees.add("minecraft:fancy_oak_bees_0002");
        trees.add("minecraft:fancy_oak_bees_002");
        trees.add("minecraft:fancy_oak_bees_005");
        trees.add("minecraft:fancy_oak_bees");
        trees.add("minecraft:fancy_oak");
        trees.add("minecraft:jungle_bush");
        trees.add("minecraft:jungle_tree");
        trees.add("minecraft:jungle_tree_no_vine");
        trees.add("minecraft:mangrove");
        trees.add("minecraft:mega_jungle_tree");
        trees.add("minecraft:mega_pine");
        trees.add("minecraft:mega_spruce");
        trees.add("minecraft:oak_bees_0002");
        trees.add("minecraft:oak_bees_002");
        trees.add("minecraft:oak_bees_005");
        trees.add("minecraft:oak");
        trees.add("minecraft:pale_oak");
        trees.add("minecraft:pine");
        trees.add("minecraft:spruce");
        trees.add("minecraft:super_birch_bees_0002");
        trees.add("minecraft:super_birch_bees");
        trees.add("minecraft:swamp_oak");
        trees.add("minecraft:tall_mangrove");
        return trees;
    }

    public static TreeDefinition importVanillaTree(String id) {
        // Parse the identifier
        String[] parts = id.split(":");
        if (parts.length != 2) {
            return null;
        }
        
        String treeName = parts[1];
        TreeDefinition def = new TreeDefinition();
        def.id = treeName;
        def.name = toTitleCase(treeName.replace("_", " "));
        def.description = "Imported from " + id;

        // Determine base tree type (strip variants like _bees, _no_vine, etc.)
        String baseType = treeName
            .replaceAll("_bees.*", "")
            .replaceAll("_no_vine", "")
            .replace("super_", "")
            .replace("tall_", "")
            .replace("mega_", "")
            .replace("fancy_", "")
            .replace("_tree", "")
            .replace("_bush", "");

        // Map to configurations
        if (baseType.equals("oak") || baseType.equals("swamp")) {
            def.trunk_block = "minecraft:oak_log";
            def.foliage_block = "minecraft:oak_leaves";
            def.trunk_height_min = treeName.contains("fancy") ? 5 : 4;
            def.trunk_height_max = treeName.contains("fancy") ? 8 : 6;
            def.foliage_radius = treeName.contains("fancy") ? 3 : 2;
            def.foliage_offset = 0;
            def.trunk_placer_type = treeName.contains("fancy") ? "forking" : "straight";
            def.foliage_placer_type = "blob";
            def.foliage_height = 3;
        } else if (baseType.equals("birch")) {
            def.trunk_block = "minecraft:birch_log";
            def.foliage_block = "minecraft:birch_leaves";
            def.trunk_height_min = treeName.contains("super") ? 8 : 5;
            def.trunk_height_max = treeName.contains("super") ? 12 : 7;
            def.foliage_radius = 2;
            def.foliage_offset = 0;
            def.trunk_placer_type = "straight";
            def.foliage_placer_type = "blob";
            def.foliage_height = 3;
        } else if (baseType.equals("spruce") || baseType.equals("pine")) {
            def.trunk_block = "minecraft:spruce_log";
            def.foliage_block = "minecraft:spruce_leaves";
            def.trunk_height_min = treeName.contains("mega") ? 10 : 6;
            def.trunk_height_max = treeName.contains("mega") ? 15 : 9;
            def.foliage_radius = treeName.contains("mega") ? 4 : 3;
            def.foliage_offset = 2;
            def.trunk_placer_type = treeName.contains("mega") ? "giant" : "straight";
            def.foliage_placer_type = baseType.equals("pine") || treeName.contains("mega") ? "mega_pine" : "spruce";
            def.foliage_height = treeName.contains("mega") ? 4 : 3;
        } else if (baseType.equals("jungle")) {
            def.trunk_block = "minecraft:jungle_log";
            def.foliage_block = "minecraft:jungle_leaves";
            if (treeName.contains("bush")) {
                def.trunk_height_min = 1;
                def.trunk_height_max = 2;
                def.foliage_radius = 2;
                def.trunk_placer_type = "straight";
                def.foliage_placer_type = "blob";
                def.foliage_height = 2;
            } else if (treeName.contains("mega")) {
                def.trunk_height_min = 12;
                def.trunk_height_max = 18;
                def.foliage_radius = 4;
                def.trunk_placer_type = "mega_jungle";
                def.foliage_placer_type = "jungle";
                def.foliage_height = 4;
            } else {
                def.trunk_height_min = 8;
                def.trunk_height_max = 12;
                def.foliage_radius = 3;
                def.trunk_placer_type = "straight";
                def.foliage_placer_type = "jungle";
                def.foliage_height = 3;
            }
            def.foliage_offset = 1;
        } else if (baseType.equals("acacia")) {
            def.trunk_block = "minecraft:acacia_log";
            def.foliage_block = "minecraft:acacia_leaves";
            def.trunk_height_min = 5;
            def.trunk_height_max = 7;
            def.foliage_radius = 2;
            def.foliage_offset = 0;
            def.trunk_placer_type = "forking";
            def.foliage_placer_type = "acacia";
            def.foliage_height = 2;
        } else if (baseType.equals("dark_oak")) {
            def.trunk_block = "minecraft:dark_oak_log";
            def.foliage_block = "minecraft:dark_oak_leaves";
            def.trunk_height_min = 6;
            def.trunk_height_max = 8;
            def.foliage_radius = 3;
            def.foliage_offset = 0;
            def.trunk_placer_type = "dark_oak";
            def.foliage_placer_type = "dark_oak";
            def.foliage_height = 4;
        } else if (baseType.equals("cherry")) {
            def.trunk_block = "minecraft:cherry_log";
            def.foliage_block = "minecraft:cherry_leaves";
            def.trunk_height_min = 5;
            def.trunk_height_max = 7;
            def.foliage_radius = 2;
            def.foliage_offset = 0;
            def.trunk_placer_type = "cherry";
            def.foliage_placer_type = "cherry";
            def.foliage_height = 4;
        } else if (baseType.equals("mangrove")) {
            def.trunk_block = "minecraft:mangrove_log";
            def.foliage_block = "minecraft:mangrove_leaves";
            def.trunk_height_min = treeName.contains("tall") ? 8 : 5;
            def.trunk_height_max = treeName.contains("tall") ? 12 : 8;
            def.foliage_radius = 2;
            def.foliage_offset = 1;
            def.trunk_placer_type = "upwards_branching";
            def.foliage_placer_type = "random_spread";
            def.foliage_height = 3;
        } else if (baseType.equals("azalea")) {
            def.trunk_block = "minecraft:oak_log";
            def.foliage_block = "minecraft:azalea_leaves";
            def.trunk_height_min = 4;
            def.trunk_height_max = 6;
            def.foliage_radius = 2;
            def.foliage_offset = 0;
            def.trunk_placer_type = "straight";
            def.foliage_placer_type = "random_spread";
            def.foliage_height = 3;
        } else if (baseType.equals("pale_oak")) {
            def.trunk_block = "minecraft:pale_oak_log";
            def.foliage_block = "minecraft:pale_oak_leaves";
            def.trunk_height_min = 6;
            def.trunk_height_max = 9;
            def.foliage_radius = 3;
            def.foliage_offset = 1;
            def.trunk_placer_type = "upwards_branching";
            def.foliage_placer_type = "cherry";
            def.foliage_height = 4;
        } else {
            // Default to oak-like tree
            def.trunk_block = "minecraft:oak_log";
            def.foliage_block = "minecraft:oak_leaves";
            def.trunk_height_min = 4;
            def.trunk_height_max = 6;
            def.foliage_radius = 2;
            def.foliage_offset = 0;
            def.trunk_placer_type = "straight";
            def.foliage_placer_type = "blob";
            def.foliage_height = 3;
        }

        return def;
    }
    
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
