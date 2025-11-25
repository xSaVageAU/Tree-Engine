package savage.tree_engine.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.TreeConfigManager;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class TreeApiHandler implements HttpHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATAPACK_DIR = Paths.get("config", "tree_engine", "datapacks", "your_pack", "data", "tree_engine", "worldgen", "configured_feature");
    private final MinecraftServer minecraftServer;

    public TreeApiHandler(MinecraftServer server) {
        this.minecraftServer = server;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        
        // Remove trailing slash
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        
        String[] parts = path.split("/");
        // Path is like /api/trees or /api/trees/{id}
        // parts[0] = "", parts[1] = "api", parts[2] = "trees"
        
        try {
            if (parts.length >= 3) {
                String endpoint = parts[2];
                
                if ("trees".equals(endpoint)) {
                    if (parts.length == 3) {
                        if ("GET".equals(method)) handleList(exchange);
                        else if ("POST".equals(method)) handleSave(exchange);
                        else sendError(exchange, 405, "Method not allowed");
                    } else if (parts.length == 4) {
                        String id = parts[3];
                        if ("GET".equals(method)) handleGet(exchange, id);
                        else if ("DELETE".equals(method)) handleDelete(exchange, id);
                        else if ("PUT".equals(method) || "POST".equals(method)) handleSave(exchange);
                        else sendError(exchange, 405, "Method not allowed");
                    }
                } else if ("vanilla_trees".equals(endpoint)) {
                    if ("GET".equals(method)) handleListVanilla(exchange);
                    else sendError(exchange, 405, "Method not allowed");
                } else if ("vanilla_tree".equals(endpoint)) {
                    // GET /api/vanilla_tree/{id} - fetch raw JSON from Minecraft registry
                    if (parts.length == 4 && "GET".equals(method)) {
                        handleGetVanillaTree(exchange, parts[3]);
                    } else {
                        sendError(exchange, 405, "Method not allowed");
                    }
                } else {
                    sendError(exchange, 404, "Not found");
                }
            } else {
                sendError(exchange, 404, "Not found");
            }
        } catch (Exception e) {
            TreeEngine.LOGGER.error("API Error", e);
            sendError(exchange, 500, "Internal Server Error: " + e.getMessage());
        }
    }

    private void handleListVanilla(HttpExchange exchange) throws IOException {
        List<String> trees = List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "azalea", "mangrove");
        String json = GSON.toJson(trees);
        sendResponse(exchange, 200, json);
    }

    /**
     * Fetch vanilla tree config from Minecraft's runtime registry.
     * This ensures we get a complete, validated config with all defaults filled in.
     */
    private void handleGetVanillaTree(HttpExchange exchange, String treeId) throws IOException {
        try {
            Identifier featureId = Identifier.of("minecraft", treeId);

            // Get the configured feature from the runtime registry
            var registryOpt = minecraftServer.getRegistryManager().getOptional(net.minecraft.registry.RegistryKeys.CONFIGURED_FEATURE);
            if (registryOpt.isEmpty()) {
                sendError(exchange, 500, "Registry not available");
                return;
            }

            net.minecraft.registry.Registry<net.minecraft.world.gen.feature.ConfiguredFeature<?, ?>> registry = registryOpt.get();
            ConfiguredFeature<?, ?> feature = registry.get(featureId);

            if (feature == null) {
                sendError(exchange, 404, "Vanilla tree not found in registry: " + treeId);
                return;
            }

            // Check if it's a tree feature
            if (!(feature.feature() instanceof net.minecraft.world.gen.feature.TreeFeature)) {
                sendError(exchange, 400, "Feature is not a tree: " + treeId);
                return;
            }

            TreeFeatureConfig config = (TreeFeatureConfig) feature.config();

            // Turn it into JSON to send to frontend
            DataResult<JsonElement> result = TreeFeatureConfig.CODEC.encodeStart(JsonOps.INSTANCE, config);
            JsonElement json = result.getOrThrow(s -> new RuntimeException("Failed to encode tree config: " + s));

            sendResponse(exchange, 200, json.toString());

        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to fetch vanilla tree from registry: " + treeId, e);
            sendError(exchange, 500, "Failed to fetch vanilla tree: " + e.getMessage());
        }
    }


    private void handleList(HttpExchange exchange) throws IOException {
        try {
            Files.createDirectories(DATAPACK_DIR);
            List<String> treeIds = Files.list(DATAPACK_DIR)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> p.getFileName().toString().replace(".json", ""))
                .collect(Collectors.toList());
            String json = GSON.toJson(treeIds);
            sendResponse(exchange, 200, json);
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to list trees", e);
            sendError(exchange, 500, "Failed to list trees");
        }
    }

    private void handleGet(HttpExchange exchange, String id) throws IOException {
        try {
            Path file = DATAPACK_DIR.resolve(id + ".json");
            if (!Files.exists(file)) {
                sendError(exchange, 404, "Tree not found");
                return;
            }
            TreeFeatureConfig config = TreeConfigManager.loadTree(file);
            DataResult<JsonElement> result = TreeFeatureConfig.CODEC.encodeStart(JsonOps.INSTANCE, config);
            JsonElement configJson = result.getOrThrow(s -> new RuntimeException("Failed to encode tree config: " + s));

            // Wrap in full configured feature format (without id, since it's the filename)
            JsonObject full = new JsonObject();
            full.addProperty("type", "minecraft:tree");
            full.add("config", configJson);

            sendResponse(exchange, 200, full.toString());
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to get tree: " + id, e);
            sendError(exchange, 500, "Failed to get tree");
        }
    }

    private void handleSave(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            JsonElement json = JsonParser.parseReader(reader);
            JsonObject obj = json.getAsJsonObject();
            JsonElement configJson = obj.get("config");

            DataResult<TreeFeatureConfig> result = TreeFeatureConfig.CODEC.parse(JsonOps.INSTANCE, configJson);
            TreeFeatureConfig config = result.getOrThrow(s -> new RuntimeException("Failed to parse tree config: " + s));

            String id = exchange.getRequestURI().getPath().split("/")[3]; // from /api/trees/{id}
            if (id == null || id.isEmpty()) {
                id = "tree_" + System.currentTimeMillis();
            }

            Path file = DATAPACK_DIR.resolve(id + ".json");
            Files.createDirectories(DATAPACK_DIR);

            // Create full configured feature JSON
            JsonObject fullJson = new JsonObject();
            fullJson.addProperty("type", "minecraft:tree");
            DataResult<JsonElement> configResult = TreeFeatureConfig.CODEC.encodeStart(JsonOps.INSTANCE, config);
            JsonElement encodedConfigJson = configResult.getOrThrow(s -> new RuntimeException("Failed to encode tree config: " + s));
            fullJson.add("config", encodedConfigJson);

            // Write the full JSON to file
            try (java.io.FileWriter writer = new java.io.FileWriter(file.toFile())) {
                GSON.toJson(fullJson, writer);
            }

            sendResponse(exchange, 200, "{\"id\": \"" + id + "\"}");
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to save tree", e);
            sendError(exchange, 400, "Invalid JSON");
        }
    }

    private void handleDelete(HttpExchange exchange, String id) throws IOException {
        try {
            Path file = DATAPACK_DIR.resolve(id + ".json");
            if (Files.deleteIfExists(file)) {
                sendResponse(exchange, 200, "{\"success\": true}");
            } else {
                sendError(exchange, 404, "Tree not found");
            }
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to delete tree: " + id, e);
            sendError(exchange, 500, "Failed to delete tree");
        }
    }

    private void sendResponse(HttpExchange exchange, int code, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        String json = "{\"error\": \"" + message + "\"}";
        sendResponse(exchange, code, json);
    }
}
