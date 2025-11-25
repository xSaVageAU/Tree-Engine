package savage.tree_engine.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import savage.tree_engine.TreeEngine;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;

public class TreeConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static TreeFeatureConfig loadTree(Path jsonFile) {
        try (Reader reader = Files.newBufferedReader(jsonFile)) {
            JsonElement json = JsonParser.parseReader(reader);

            // The file contains full configured feature JSON: {"type": "minecraft:tree", "config": {...}}
            // Extract the config part
            JsonObject obj = json.getAsJsonObject();
            JsonElement configJson = obj.get("config");

            // This ONE line does all the parsing, validation, and object creation
            // exactly how Minecraft does it. If this fails, your JSON is invalid.
            DataResult<TreeFeatureConfig> result = TreeFeatureConfig.CODEC.parse(JsonOps.INSTANCE, configJson);

            return result.getOrThrow(s -> new RuntimeException("Failed to parse tree config: " + s));
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to read tree config file: " + jsonFile, e);
            throw new RuntimeException(e);
        }
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