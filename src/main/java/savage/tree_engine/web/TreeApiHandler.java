package savage.tree_engine.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.minecraft.resource.Resource;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.TreeConfigManager;
import savage.tree_engine.config.TreeWrapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TreeApiHandler implements HttpHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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
                        else if ("PUT".equals(method)) handleSave(exchange);
                        else sendError(exchange, 405, "Method not allowed");
                    }
                } else if ("vanilla_trees".equals(endpoint)) {
                    if ("GET".equals(method)) handleListVanilla(exchange);
                    else sendError(exchange, 405, "Method not allowed");
                } else if ("vanilla_tree".equals(endpoint)) {
                    // GET /api/vanilla_tree/{id} - fetch raw JSON from Minecraft resources
                    if (parts.length == 4 && "GET".equals(method)) {
                        handleGetVanillaTree(exchange, parts[3]);
                    } else {
                        sendError(exchange, 405, "Method not allowed");
                    }
                } else if ("schema".equals(endpoint)) {
                    // GET /api/schema - serve the tree feature JSON schema
                    if ("GET".equals(method)) {
                        handleGetSchema(exchange);
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
        List<String> trees = TreeConfigManager.getVanillaTrees();
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
            net.minecraft.world.gen.feature.ConfiguredFeature<?, ?> configuredFeature = registry.get(featureId);
            
            if (configuredFeature == null) {
                sendError(exchange, 404, "Vanilla tree not found in registry: " + treeId);
                return;
            }
            
            // Check if it's a tree feature
            if (!(configuredFeature.feature() instanceof net.minecraft.world.gen.feature.TreeFeature)) {
                sendError(exchange, 400, "Feature is not a tree: " + treeId);
                return;
            }
            
            // Encode the config back to JSON using the codec
            // This gives us a complete, valid JSON representation
            com.mojang.serialization.DataResult<com.google.gson.JsonElement> result = 
                net.minecraft.world.gen.feature.TreeFeatureConfig.CODEC.encodeStart(
                    com.mojang.serialization.JsonOps.INSTANCE, 
                    (net.minecraft.world.gen.feature.TreeFeatureConfig) configuredFeature.config()
                );
            
            com.google.gson.JsonElement configJson = result.getOrThrow(errorMsg -> new IllegalStateException("Failed to encode tree config: " + errorMsg));
            
            // Wrap it in the expected format
            com.google.gson.JsonObject wrapper = new com.google.gson.JsonObject();
            wrapper.addProperty("type", "minecraft:tree");
            wrapper.add("config", configJson);
            
            String json = GSON.toJson(wrapper);
            sendResponse(exchange, 200, json);
            
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to fetch vanilla tree from registry: " + treeId, e);
            sendError(exchange, 500, "Failed to fetch vanilla tree: " + e.getMessage());
        }
    }

    /**
     * Serve the tree feature JSON schema for dynamic UI generation.
     */
    private void handleGetSchema(HttpExchange exchange) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("schemas/tree_feature_schema.json")) {
            if (is == null) {
                sendError(exchange, 404, "Schema file not found");
                return;
            }
            
            String json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            sendResponse(exchange, 200, json);
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to load schema", e);
            sendError(exchange, 500, "Failed to load schema: " + e.getMessage());
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        Map<String, TreeWrapper> trees = TreeConfigManager.getTrees();
        List<TreeWrapper> list = new ArrayList<>(trees.values());
        String json = GSON.toJson(list);
        sendResponse(exchange, 200, json);
    }

    private void handleGet(HttpExchange exchange, String id) throws IOException {
        TreeWrapper tree = TreeConfigManager.getTree(id);
        if (tree == null) {
            sendError(exchange, 404, "Tree not found");
            return;
        }
        String json = GSON.toJson(tree);
        sendResponse(exchange, 200, json);
    }

    private void handleSave(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            TreeWrapper tree = GSON.fromJson(reader, TreeWrapper.class);
            
            if (tree.id == null || tree.id.isEmpty()) {
                if (tree.name != null) {
                    tree.id = tree.name.toLowerCase().replace(" ", "_").replaceAll("[^a-z0-9_]", "");
                } else {
                    tree.id = "tree_" + System.currentTimeMillis();
                }
            }

            TreeConfigManager.saveTree(tree);
            String json = GSON.toJson(tree);
            sendResponse(exchange, 200, json);
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to save tree", e);
            sendError(exchange, 400, "Invalid JSON");
        }
    }

    private void handleDelete(HttpExchange exchange, String id) throws IOException {
        if (TreeConfigManager.deleteTree(id)) {
            sendResponse(exchange, 200, "{\"success\": true}");
        } else {
            sendError(exchange, 404, "Tree not found");
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
