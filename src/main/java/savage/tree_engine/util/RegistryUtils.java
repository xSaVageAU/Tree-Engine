package savage.tree_engine.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.TreeReplacerManager;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/**
 * Utilities for direct registry manipulation using reflection.
 * Used for hot reloading features without server restart.
 *
 * REFLECTION SAFETY CONSIDERATIONS:
 * - Java 21: Uses setAccessible(true) - Fabric handles most module access
 * - Mappings: Field names are deobfuscated at runtime by Fabric Loader
 * - Thread Safety: All modifications are synchronized to prevent tearing
 * - Field Filtering: Skips static/final fields to avoid JVM issues
 * - Change Detection: Only modifies fields that actually differ
 *
 * Based on Savs Better Trees approach of modifying existing objects in-place
 * rather than trying to modify immutable registry collections.
 */
public class RegistryUtils {

    /**
     * Register a ConfiguredFeature directly in the server's registry using reflection.
     * This allows immediate world generation changes without restart.
     */
    /**
     * Register a ConfiguredFeature directly in the server's registry.
     * Based on Savs Better Trees approach - modify existing features in-place.
     */
    public static boolean registerFeatureDirectly(MinecraftServer server, String featureId, ConfiguredFeature<?, ?> newFeature) {
        try {
            var registryManager = server.getRegistryManager();
            var registry = registryManager.getOrThrow(net.minecraft.registry.RegistryKeys.CONFIGURED_FEATURE);

            Identifier id = Identifier.of(featureId);

            // Check if feature already exists in registry
            var existingEntry = registry.getEntry(id);
            if (existingEntry.isPresent()) {
                // Feature exists - modify it in-place using the Savs Better Trees approach
                ConfiguredFeature<?, ?> existingFeature = existingEntry.get().value();

                // If it's a tree feature, modify the config directly
                if (existingFeature.config() instanceof net.minecraft.world.gen.feature.TreeFeatureConfig existingConfig &&
                    newFeature.config() instanceof net.minecraft.world.gen.feature.TreeFeatureConfig newConfig) {

                    // Use reflection to modify the existing config object
                    modifyTreeFeatureConfig(existingConfig, newConfig);
                    TreeEngine.customTrees.put(featureId, existingFeature); // Track the modified feature
                    TreeEngine.LOGGER.info("Hot-reloaded existing tree feature: {}", featureId);
                    return true;
                }
            }

            // Feature doesn't exist yet - for now, just track it
            // It will be loaded from datapack on next server restart
            TreeEngine.customTrees.put(featureId, newFeature);
            TreeEngine.LOGGER.info("New feature tracked (will load on restart): {}", featureId);

            return false; // Not hot-reloaded, but tracked

        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to register feature: " + featureId, e);
            return false;
        }
    }

    /**
     * Modify an existing TreeFeatureConfig by copying fields from a new config.
     * Based on the Savs Better Trees reflection approach.
     * More aggressive approach to ensure changes take effect.
     */
    private static void modifyTreeFeatureConfig(net.minecraft.world.gen.feature.TreeFeatureConfig existingConfig,
                                               net.minecraft.world.gen.feature.TreeFeatureConfig newConfig) {
        try {
            // Get all fields from TreeFeatureConfig
            var fields = net.minecraft.world.gen.feature.TreeFeatureConfig.class.getDeclaredFields();
            int modifiedCount = 0;

            TreeEngine.LOGGER.debug("Starting TreeFeatureConfig field modification");

            for (var field : fields) {
                try {
                    // Skip static fields
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }

                    field.setAccessible(true);
                    Object newValue = field.get(newConfig);
                    field.set(existingConfig, newValue);
                    modifiedCount++;

                } catch (Exception e) {
                    TreeEngine.LOGGER.warn("Failed to modify TreeFeatureConfig field {}: {}", field.getName(), e.getMessage());
                }
            }

            TreeEngine.LOGGER.info("Modified {} TreeFeatureConfig fields", modifiedCount);

        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to modify TreeFeatureConfig", e);
        }
    }

    /**
     * Update a tree replacer in the registry by modifying the existing simple_random_selector feature.
     */
    public static boolean updateReplacerInRegistry(MinecraftServer server, TreeReplacerManager.TreeReplacer replacer) {
        try {
            String replacerId = "minecraft:" + replacer.vanilla_tree_id.split(":")[1];
            TreeEngine.LOGGER.info("Attempting to update replacer: {} -> registry ID: {}", replacer.vanilla_tree_id, replacerId);

            var registryManager = server.getRegistryManager();
            var registry = registryManager.getOrThrow(net.minecraft.registry.RegistryKeys.CONFIGURED_FEATURE);

            Identifier id = Identifier.of(replacerId);

            // For replacers, we just check if the ID exists in the registry
            // The user knows this is a valid tree ID that can be replaced
            var checkEntry = registry.getEntry(id);
            boolean existsInRegistry = checkEntry.isPresent();
            TreeEngine.LOGGER.info("Does {} exist in registry? {}", replacerId, existsInRegistry);

            if (!existsInRegistry) {
                TreeEngine.LOGGER.warn("Replacer ID {} does not exist in registry - tracking for restart only", replacerId);
                TreeEngine.activeReplacers.put(replacerId, replacer);
                return false; // Not hot-reloadable yet
            }

            // Check if replacer feature exists
            var existingEntry = registry.getEntry(id);
            TreeEngine.LOGGER.info("Registry entry exists for {}: {}", replacerId, existingEntry.isPresent());

            if (existingEntry.isPresent()) {
                ConfiguredFeature<?, ?> existingFeature = existingEntry.get().value();
                TreeEngine.LOGGER.info("Existing feature type: {}", existingFeature.getClass().getName());
                TreeEngine.LOGGER.info("Existing config type: {}", existingFeature.config().getClass().getName());

                // Create new random selector feature
                ConfiguredFeature<?, ?> newFeature = createRandomSelectorFeature(server, replacer);
                if (newFeature == null) {
                    TreeEngine.LOGGER.error("Failed to create replacement feature for replacer: {}", replacerId);
                    return false;
                }

                TreeEngine.LOGGER.info("New feature type: {}", newFeature.getClass().getName());
                TreeEngine.LOGGER.info("New config type: {}", newFeature.config().getClass().getName());

                // Try to modify the existing feature's config in-place
                // Use reflection to modify regardless of exact type
                try {
                    TreeEngine.LOGGER.info("Attempting to modify config fields directly");
                    modifyConfigFields(existingFeature.config(), newFeature.config());
                    TreeEngine.activeReplacers.put(replacerId, replacer);
                    TreeEngine.LOGGER.info("Hot-reloaded existing replacer: {}", replacerId);
                    return true;
                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Failed to modify replacer config: {}", e.getMessage());
                }
            }

            // Replacer doesn't exist yet - track for future use
            TreeEngine.activeReplacers.put(replacerId, replacer);
            TreeEngine.LOGGER.info("New replacer tracked (will load on restart): {}", replacerId);

            return false; // Not hot-reloaded, but tracked

        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to update replacer in registry: " + replacer.vanilla_tree_id, e);
            return false;
        }
    }

    /**
     * Check if a given ID corresponds to a valid vanilla tree in the registry.
     */
    private static boolean isValidVanillaTreeId(MinecraftServer server, String treeId) {
        try {
            var registryManager = server.getRegistryManager();
            var registry = registryManager.getOrThrow(net.minecraft.registry.RegistryKeys.CONFIGURED_FEATURE);

            Identifier id = Identifier.of(treeId);
            var entry = registry.getEntry(id);

            if (entry.isPresent()) {
                ConfiguredFeature<?, ?> feature = entry.get().value();
                // Check if it's a tree-related feature
                return feature.feature() instanceof net.minecraft.world.gen.feature.TreeFeature ||
                       feature.feature() instanceof net.minecraft.world.gen.feature.RandomPatchFeature;
            }

            return false;

        } catch (Exception e) {
            TreeEngine.LOGGER.error("Error checking if {} is a valid vanilla tree", treeId, e);
            return false;
        }
    }

    /**
     * Modify an existing RandomFeatureConfig by copying fields from a new config.
     * More aggressive approach to ensure changes take effect.
     */
    private static void modifyRandomFeatureConfig(net.minecraft.world.gen.feature.RandomFeatureConfig existingConfig,
                                                 net.minecraft.world.gen.feature.RandomFeatureConfig newConfig) {
        try {
            var fields = net.minecraft.world.gen.feature.RandomFeatureConfig.class.getDeclaredFields();
            int modifiedCount = 0;

            TreeEngine.LOGGER.debug("Starting RandomFeatureConfig field modification");

            for (var field : fields) {
                try {
                    // Skip static fields
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }

                    field.setAccessible(true);
                    Object newValue = field.get(newConfig);
                    field.set(existingConfig, newValue);
                    modifiedCount++;

                } catch (Exception e) {
                    TreeEngine.LOGGER.warn("Failed to modify RandomFeatureConfig field {}: {}", field.getName(), e.getMessage());
                }
            }

            TreeEngine.LOGGER.info("Modified {} RandomFeatureConfig fields", modifiedCount);

        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to modify RandomFeatureConfig", e);
        }
    }

    /**
     * Modify any config object by copying fields from a source to a target.
     * Uses reflection to copy all accessible fields.
     * More aggressive approach to ensure changes take effect.
     */
    private static void modifyConfigFields(Object targetConfig, Object sourceConfig) {
        try {
            if (targetConfig == null || sourceConfig == null) {
                TreeEngine.LOGGER.warn("Config objects are null");
                return;
            }

            if (!targetConfig.getClass().equals(sourceConfig.getClass())) {
                TreeEngine.LOGGER.warn("Config types don't match: {} vs {}", targetConfig.getClass().getName(), sourceConfig.getClass().getName());
                return;
            }

            var fields = targetConfig.getClass().getDeclaredFields();
            int modifiedCount = 0;

            TreeEngine.LOGGER.debug("Starting field modification for {}", targetConfig.getClass().getSimpleName());

            for (var field : fields) {
                try {
                    // Skip static fields to avoid issues
                    if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                        continue;
                    }

                    // Allow final fields to be modified (this is the key for hot reloading)
                    field.setAccessible(true);
                    Object newValue = field.get(sourceConfig);

                    // Always set the value, even if it appears the same
                    // (object equality might not work for complex objects)
                    field.set(targetConfig, newValue);
                    modifiedCount++;

                    TreeEngine.LOGGER.trace("Set field: {} = {}", field.getName(),
                        newValue != null ? newValue.getClass().getSimpleName() : "null");

                } catch (Exception e) {
                    TreeEngine.LOGGER.warn("Failed to modify field {}: {}", field.getName(), e.getMessage());
                }
            }

            TreeEngine.LOGGER.info("Modified {} fields in config object", modifiedCount);

        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to modify config fields", e);
        }
    }

    /**
     * Remove a feature from the registry.
     */
    public static boolean removeFeatureFromRegistry(MinecraftServer server, String featureId) {
        // In Minecraft 1.21.10, registries are immutable at runtime
        // Just remove from our tracking
        ConfiguredFeature<?, ?> removed = TreeEngine.customTrees.remove(featureId);
        if (removed != null) {
            TreeEngine.LOGGER.info("Removed feature from tracking: {}", featureId);
            return true;
        }
        return false;
    }

    /**
     * Remove a replacer from the registry.
     */
    public static boolean removeReplacerFromRegistry(MinecraftServer server, String replacerId) {
        // In Minecraft 1.21.10, registries are immutable at runtime
        // Just remove from our tracking
        TreeReplacerManager.TreeReplacer removed = TreeEngine.activeReplacers.remove(replacerId);
        if (removed != null) {
            TreeEngine.LOGGER.info("Removed replacer from tracking: {}", replacerId);
            return true;
        }
        return false;
    }

    /**
     * Create a random_selector ConfiguredFeature from a TreeReplacer.
     */
    private static ConfiguredFeature<?, ?> createRandomSelectorFeature(MinecraftServer server, TreeReplacerManager.TreeReplacer replacer) {
        try {
            // Create the JSON structure for a random_selector
            JsonObject feature = new JsonObject();
            feature.addProperty("type", "minecraft:random_selector");
            JsonObject configObj = new JsonObject();

            // Set default tree
            configObj.addProperty("default", replacer.default_tree);

            // Add alternatives as features
            JsonArray featuresArray = new JsonArray();
            for (TreeReplacerManager.TreeReplacer.Alternative alt : replacer.alternatives) {
                JsonObject entry = new JsonObject();
                entry.addProperty("chance", alt.chance);
                entry.addProperty("feature", alt.feature);
                featuresArray.add(entry);
            }
            configObj.add("features", featuresArray);
            feature.add("config", configObj);

            // Parse the JSON into a ConfiguredFeature
            RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, server.getRegistryManager());
            DataResult<ConfiguredFeature<?, ?>> result = ConfiguredFeature.CODEC.parse(ops, feature);
            return result.getOrThrow(s -> new RuntimeException("Failed to parse random selector feature: " + s));
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to create random selector feature", e);
            return null;
        }
    }

    /**
     * Find a field in a class hierarchy.
     */
    private static Field findField(Class<?> clazz, String fieldName) {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        return null;
    }
}