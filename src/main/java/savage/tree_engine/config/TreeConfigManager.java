package savage.tree_engine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryOps;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import savage.tree_engine.TreeEngine;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public class TreeConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /**
     * Load the full configured feature from JSON (preserves random_patch, selectors, etc.)
     * This is the preferred method for 1:1 datapack-accurate generation.
     */
    public static ConfiguredFeature<?, ?> loadConfiguredFeature(Path jsonFile, DynamicRegistryManager registryManager) {
        try (Reader reader = Files.newBufferedReader(jsonFile)) {
            JsonElement json = JsonParser.parseReader(reader);
            
            // Use RegistryOps to parse with registry context
            RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, registryManager);
            
            // Parse the full configured feature
            DataResult<ConfiguredFeature<?, ?>> result = ConfiguredFeature.CODEC.parse(ops, json);
            
            return result.getOrThrow(s -> new RuntimeException("Failed to parse configured feature: " + s));
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to read feature file: " + jsonFile, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Load just the tree config by unwrapping feature wrappers.
     * Use loadConfiguredFeature() instead for full datapack-accurate generation.
     */
    public static TreeFeatureConfig loadTree(Path jsonFile) {
        try (Reader reader = Files.newBufferedReader(jsonFile)) {
            JsonElement json = JsonParser.parseReader(reader);
            JsonObject obj = json.getAsJsonObject();
            
            // Recursively unwrap feature wrappers until we find a tree
            JsonElement configJson = unwrapToTreeConfig(obj);

            // Parse the tree config using Minecraft's codec
            DataResult<TreeFeatureConfig> result = TreeFeatureConfig.CODEC.parse(JsonOps.INSTANCE, configJson);
            return result.getOrThrow(s -> new RuntimeException("Failed to parse tree config: " + s));
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to read tree config file: " + jsonFile, e);
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Recursively unwraps feature wrappers (simple_random_selector, random_patch) 
     * until we find an actual minecraft:tree config.
     */
    private static JsonElement unwrapToTreeConfig(JsonObject feature) {
        String type = feature.has("type") ? feature.get("type").getAsString() : "";
        
        if (type.equals("minecraft:tree")) {
            // Found a tree! Return its config
            return feature.get("config");
        }
        
        if (type.equals("minecraft:simple_random_selector")) {
            // Extract first feature from the selector and recurse
            JsonObject config = feature.getAsJsonObject("config");
            if (config == null || !config.has("features")) {
                throw new RuntimeException("simple_random_selector missing features array");
            }
            
            JsonObject firstFeature = config.getAsJsonArray("features")
                .get(0).getAsJsonObject()
                .getAsJsonObject("feature");
            
            TreeEngine.LOGGER.info("Unwrapping simple_random_selector...");
            return unwrapToTreeConfig(firstFeature);
        }
        
        if (type.equals("minecraft:random_patch")) {
            // Extract the inner feature from random_patch and recurse
            JsonObject config = feature.getAsJsonObject("config");
            if (config == null || !config.has("feature")) {
                throw new RuntimeException("random_patch missing feature");
            }
            
            JsonObject innerFeature = config.getAsJsonObject("feature")
                .getAsJsonObject("feature");
            
            TreeEngine.LOGGER.info("Unwrapping random_patch...");
            return unwrapToTreeConfig(innerFeature);
        }
        
        throw new RuntimeException("Unsupported feature type: " + type + 
            ". Only 'minecraft:tree' and wrappers (simple_random_selector, random_patch) are supported.");
    }

    public static void saveTree(Path jsonFile, TreeFeatureConfig config) {
        // This ONE line converts the complex Minecraft object back to JSON
        DataResult<JsonElement> result = TreeFeatureConfig.CODEC.encodeStart(JsonOps.INSTANCE, config);

        JsonElement json = result.getOrThrow(s -> new RuntimeException("Failed to encode tree config: " + s));

        // Write 'json' to file using Gson just for pretty printing
        try {
            Files.writeString(jsonFile, GSON.toJson(json));
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to write tree config file: " + jsonFile, e);
            throw new RuntimeException(e);
        }
    }
}