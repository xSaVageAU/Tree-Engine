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
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.TreeConfigManager;
import savage.tree_engine.config.TreeReplacerManager;
import savage.tree_engine.config.MainConfig;
import savage.tree_engine.util.RegistryUtils;

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
import savage.tree_engine.web.PathValidator;

public class TreeApiHandler implements HttpHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path DATAPACK_DIR = Paths.get("config", "tree_engine", "datapacks", "tree_engine_trees", "data", "tree_engine", "worldgen", "configured_feature");
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
                    // ID might contain slashes (e.g. wythers:biomes/forest/oak), so we can't just use parts[3]
                    if ("GET".equals(method)) {
                        String requestPath = exchange.getRequestURI().getPath();
                        String prefix = "/api/vanilla_tree/";
                        if (requestPath.startsWith(prefix)) {
                            String id = requestPath.substring(prefix.length());
                            handleGetVanillaTree(exchange, id);
                        } else {
                            sendError(exchange, 400, "Invalid ID");
                        }
                    } else {
                        sendError(exchange, 405, "Method not allowed");
                    }
                } else if ("replacers".equals(endpoint)) {
                    if (parts.length == 3) {
                        if ("GET".equals(method)) handleListReplacers(exchange);
                        else if ("POST".equals(method)) handleSaveReplacer(exchange);
                        else sendError(exchange, 405, "Method not allowed");
                    } else if (parts.length == 4) {
                        String id = parts[3];
                        if ("GET".equals(method)) handleGetReplacer(exchange, id);
                        else if ("DELETE".equals(method)) handleDeleteReplacer(exchange, id);
                        else sendError(exchange, 405, "Method not allowed");
                    }
                } else if ("hot-reload".equals(endpoint)) {
                    if ("POST".equals(method)) handleHotReload(exchange);
                    else sendError(exchange, 405, "Method not allowed");
                } else {
                    sendError(exchange, 404, "Not found");
                }
            } else {
                sendError(exchange, 404, "Not found");
            }
        } catch (Exception e) {
            TreeEngine.LOGGER.error("API Error: {}", exchange.getRequestURI().getPath(), e);
            sendResponse(exchange, 500, "{\"error\":\"Internal server error\"}");
        }
    }

    private void handleListVanilla(HttpExchange exchange) throws IOException {
        try {
            var registryOpt = minecraftServer.getRegistryManager().getOptional(net.minecraft.registry.RegistryKeys.CONFIGURED_FEATURE);
            if (registryOpt.isEmpty()) {
                sendError(exchange, 500, "Registry not available");
                return;
            }

            net.minecraft.registry.Registry<ConfiguredFeature<?, ?>> registry = registryOpt.get();
            List<String> treeIds = new ArrayList<>();

            for (var entry : registry.getEntrySet()) {
                Identifier id = entry.getKey().getValue();
                
                // Filter for vanilla trees only as requested
                if (!id.getNamespace().equals("minecraft")) {
                    continue;
                }

                ConfiguredFeature<?, ?> feature = entry.getValue();
                
                // Check if it's a tree-related feature
                // We include TreeFeature and RandomPatch (often used for trees)
                if (feature.feature() instanceof net.minecraft.world.gen.feature.TreeFeature ||
                    feature.feature() instanceof net.minecraft.world.gen.feature.RandomPatchFeature) {
                    treeIds.add(id.toString());
                }
            }
            
            // Sort alphabetically
            java.util.Collections.sort(treeIds);

            String json = GSON.toJson(treeIds);
            sendResponse(exchange, 200, json);
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to list vanilla trees", e);
            sendResponse(exchange, 500, "{\"error\":\"Failed to list trees\"}");
        }
    }

    /**
     * Fetch vanilla tree config from Minecraft's runtime registry.
     * This ensures we get a complete, validated config with all defaults filled in.
     */
    private void handleGetVanillaTree(HttpExchange exchange, String treeId) throws IOException {
        try {
            // Parse the ID (handles namespaces like "minecraft:oak")
            // Note: If the ID contains slashes, the simple split("/") in the handler might have broken it.
            // But since we are now restricting to vanilla (which usually doesn't have slashes in tree IDs),
            // this is less of a risk.
            Identifier featureId = Identifier.of(treeId);

            // Get the configured feature from the runtime registry
            var registryOpt = minecraftServer.getRegistryManager().getOptional(net.minecraft.registry.RegistryKeys.CONFIGURED_FEATURE);
            if (registryOpt.isEmpty()) {
                sendError(exchange, 500, "Registry not available");
                return;
            }

            net.minecraft.registry.Registry<ConfiguredFeature<?, ?>> registry = registryOpt.get();
            ConfiguredFeature<?, ?> feature = registry.get(featureId);

            if (feature == null) {
                sendError(exchange, 404, "Tree not found in registry: " + treeId);
                return;
            }

            // Encode the FULL feature (including type and wrappers)
            // This allows us to import complex features like random_patch -> simple_random_selector -> tree
            net.minecraft.registry.RegistryOps<JsonElement> ops = net.minecraft.registry.RegistryOps.of(JsonOps.INSTANCE, minecraftServer.getRegistryManager());
            DataResult<JsonElement> result = ConfiguredFeature.CODEC.encodeStart(ops, feature);
            JsonElement json = result.getOrThrow(s -> new RuntimeException("Failed to encode feature: " + s));

            // Wrap in a structure that matches what the frontend expects for "full JSON" import
            // The frontend expects the raw JSON structure that would go in a file
            sendResponse(exchange, 200, json.toString());

        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to fetch tree from registry: {}", treeId, e);
            sendResponse(exchange, 500, "{\"error\":\"Failed to fetch tree\"}");
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
            sendResponse(exchange, 500, "{\"error\":\"Failed to list trees\"}");
        }
    }

    private void handleGet(HttpExchange exchange, String id) throws IOException {
        // Validate tree ID
        if (!InputValidator.isValidTreeId(id)) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid tree ID\"}");
            TreeEngine.LOGGER.warn("Invalid tree ID attempted: {}", id);
            return;
        }

        try {
            // Safely resolve the file path
            Path file;
            try {
                file = PathValidator.resolveSafePath(id + ".json", DATAPACK_DIR);
            } catch (SecurityException e) {
                TreeEngine.LOGGER.warn("Path traversal attempt in tree GET: {}", id);
                sendResponse(exchange, 400, "{\"error\":\"Invalid tree ID\"}");
                return;
            }

            if (!Files.exists(file)) {
                sendError(exchange, 404, "Tree not found");
                return;
            }

            // Read raw JSON file to preserve full structure (wrappers, etc.)
            String jsonContent = Files.readString(file, StandardCharsets.UTF_8);

            // Validate it's valid JSON
            try {
                JsonParser.parseString(jsonContent);
            } catch (Exception e) {
                sendError(exchange, 500, "Corrupted JSON file");
                return;
            }

            sendResponse(exchange, 200, jsonContent);
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to get tree: {}", id, e);
            sendResponse(exchange, 500, "{\"error\":\"Failed to load tree\"}");
        }
    }

    private static final Path PLACED_FEATURE_DIR = Paths.get("config", "tree_engine", "datapacks", "tree_engine_trees", "data", "tree_engine", "worldgen", "placed_feature");

    private void handleSave(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            // Read JSON as string first to validate size
            StringBuilder jsonBuilder = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                jsonBuilder.append(buffer, 0, read);
            }
            String jsonString = jsonBuilder.toString();

            // Validate JSON size
            if (!InputValidator.isValidJsonSize(jsonString)) {
                sendResponse(exchange, 413, "{\"error\":\"Payload too large\"}");
                TreeEngine.LOGGER.warn("Oversized JSON payload rejected: {} bytes", jsonString.getBytes(StandardCharsets.UTF_8).length);
                return;
            }

            JsonElement json = JsonParser.parseString(jsonString);

            if (!json.isJsonObject() || !json.getAsJsonObject().has("type")) {
                sendError(exchange, 400, "Invalid feature JSON: missing 'type' field");
                return;
            }
            String id = exchange.getRequestURI().getPath().split("/")[3];

            // Validate tree ID
            if (!InputValidator.isValidTreeId(id)) {
                sendResponse(exchange, 400, "{\"error\":\"Invalid tree ID\"}");
                TreeEngine.LOGGER.warn("Invalid tree ID in save attempt: {}", id);
                return;
            }
            if (id == null || id.isEmpty()) {
                id = "tree_" + System.currentTimeMillis();
            }

            // 1. Save ConfiguredFeature
            Path configFile = DATAPACK_DIR.resolve(id + ".json");
            Files.createDirectories(DATAPACK_DIR);
            
            // Remove 'id' field if present (cleanup)
            if (json.isJsonObject() && json.getAsJsonObject().has("id")) {
                json.getAsJsonObject().remove("id");
            }
            
            try (java.io.FileWriter writer = new java.io.FileWriter(configFile.toFile())) {
                GSON.toJson(json, writer);
            }

            // 2. Save PlacedFeature
            // We need a PlacedFeature to be able to use this tree in a simple_random_selector (Tree Replacer)
            Path placedFile = PLACED_FEATURE_DIR.resolve(id + ".json");
            Files.createDirectories(PLACED_FEATURE_DIR);
            
            JsonObject placedFeature = new JsonObject();
            placedFeature.addProperty("feature", "tree_engine:" + id);
            placedFeature.add("placement", new com.google.gson.JsonArray()); // Empty placement rules
            
            try (java.io.FileWriter writer = new java.io.FileWriter(placedFile.toFile())) {
                GSON.toJson(placedFeature, writer);
            }

            // 3. Register directly in registry for hot reloading
            if (MainConfig.get().hot_reload_enabled) {
                try {
                    RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, minecraftServer.getRegistryManager());
                    DataResult<ConfiguredFeature<?, ?>> result = ConfiguredFeature.CODEC.parse(ops, json);
                    ConfiguredFeature<?, ?> feature = result.getOrThrow(s -> new RuntimeException("Parse failed: " + s));

                    String featureId = "tree_engine:" + id;
                    boolean registered = RegistryUtils.registerFeatureDirectly(minecraftServer, featureId, feature);

                    if (registered) {
                        TreeEngine.LOGGER.info("Hot-reloaded tree: {}", featureId);
                    } else {
                        TreeEngine.LOGGER.warn("Failed to hot-reload tree: {} - continuing with file-only save", featureId);
                    }
                } catch (Exception e) {
                    TreeEngine.LOGGER.warn("Failed to register tree directly: {} - continuing with file-only save", id, e);
                }
            } else {
                TreeEngine.LOGGER.info("Hot reload disabled. Changes saved to disk only.");
            }

            sendResponse(exchange, 200, "{\"id\": \"" + id + "\"}");
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to save tree", e);
            sendResponse(exchange, 400, "{\"error\":\"Failed to save tree\"}");
        }
    }

    private void handleDelete(HttpExchange exchange, String id) throws IOException {
        // Validate tree ID
        if (!InputValidator.isValidTreeId(id)) {
            sendResponse(exchange, 400, "{\"error\":\"Invalid tree ID\"}");
            TreeEngine.LOGGER.warn("Invalid tree ID in delete attempt: {}", id);
            return;
        }

        try {
            boolean deleted = false;

            // Delete ConfiguredFeature
            Path configFile = DATAPACK_DIR.resolve(id + ".json");
            if (Files.deleteIfExists(configFile)) {
                deleted = true;
            }

            // Delete PlacedFeature
            Path placedFile = PLACED_FEATURE_DIR.resolve(id + ".json");
            Files.deleteIfExists(placedFile);

            // Remove from registry if present
            if (MainConfig.get().hot_reload_enabled) {
                String featureId = "tree_engine:" + id;
                boolean removedFromRegistry = RegistryUtils.removeFeatureFromRegistry(minecraftServer, featureId);
                if (removedFromRegistry) {
                    TreeEngine.LOGGER.info("Removed tree from registry: {}", featureId);
                }
            }

            if (deleted) {
                sendResponse(exchange, 200, "{\"success\": true}");
            } else {
                sendError(exchange, 404, "Tree not found");
            }
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to delete tree: {}", id, e);
            sendResponse(exchange, 500, "{\"error\":\"Failed to delete tree\"}");
        }
    }

    private void handleListReplacers(HttpExchange exchange) throws IOException {
        try {
            List<TreeReplacerManager.TreeReplacer> replacers = TreeReplacerManager.getAll();
            String json = GSON.toJson(replacers);
            sendResponse(exchange, 200, json);
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to list tree replacers", e);
            sendResponse(exchange, 500, "{\"error\":\"Failed to list replacers\"}");
        }
    }

    private void handleGetReplacer(HttpExchange exchange, String id) throws IOException {
        try {
            TreeReplacerManager.TreeReplacer replacer = TreeReplacerManager.get(id);
            if (replacer == null) {
                sendError(exchange, 404, "Tree replacer not found");
                return;
            }
            String json = GSON.toJson(replacer);
            sendResponse(exchange, 200, json);
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to get tree replacer: {}", id, e);
            sendResponse(exchange, 500, "{\"error\":\"Failed to load replacer\"}");
        }
    }

    private void handleSaveReplacer(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            TreeReplacerManager.TreeReplacer replacer = GSON.fromJson(reader, TreeReplacerManager.TreeReplacer.class);
            
            if (replacer.id == null || replacer.id.isEmpty()) {
                replacer.id = "replacer_" + System.currentTimeMillis();
            }
            
            if (replacer.vanilla_tree_id == null || replacer.vanilla_tree_id.isEmpty()) {
                sendError(exchange, 400, "Missing vanilla_tree_id");
                return;
            }
            
            if (replacer.replacement_pool == null || replacer.replacement_pool.isEmpty()) {
                sendError(exchange, 400, "Replacement pool cannot be empty");
                return;
            }
            
            TreeReplacerManager.saveReplacer(replacer);

            // Update registry for hot reloading
            if (MainConfig.get().hot_reload_enabled) {
                try {
                    boolean updated = RegistryUtils.updateReplacerInRegistry(minecraftServer, replacer);
                    if (updated) {
                        TreeEngine.LOGGER.info("Hot-reloaded replacer: {}", replacer.vanilla_tree_id);
                    } else {
                        TreeEngine.LOGGER.warn("Failed to hot-reload replacer: {} - continuing with file-only save", replacer.vanilla_tree_id);
                    }
                } catch (Exception e) {
                    TreeEngine.LOGGER.warn("Failed to update replacer in registry: {} - continuing with file-only save", replacer.vanilla_tree_id, e);
                }
            } else {
                TreeEngine.LOGGER.info("Hot reload disabled. Replacer saved to disk only.");
            }

            sendResponse(exchange, 200, "{\"id\": \"" + replacer.id + "\"}");
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to save tree replacer", e);
            sendResponse(exchange, 400, "{\"error\":\"Failed to save replacer\"}");
        }
    }

    private void handleDeleteReplacer(HttpExchange exchange, String id) throws IOException {
        try {
            TreeReplacerManager.TreeReplacer replacer = TreeReplacerManager.get(id);
            if (replacer == null) {
                sendError(exchange, 404, "Tree replacer not found");
                return;
            }
            
            TreeReplacerManager.delete(id);

            // Remove from registry if present
            if (MainConfig.get().hot_reload_enabled) {
                String replacerId = "minecraft:" + replacer.vanilla_tree_id.split(":")[1];
                boolean removedFromRegistry = RegistryUtils.removeReplacerFromRegistry(minecraftServer, replacerId);
                if (removedFromRegistry) {
                    TreeEngine.LOGGER.info("Removed replacer from registry: {}", replacerId);
                }
            }

            sendResponse(exchange, 200, "{\"success\": true}");
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to delete tree replacer: {}", id, e);
            sendResponse(exchange, 500, "{\"error\":\"Failed to delete replacer\"}");
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

    private void handleHotReload(HttpExchange exchange) throws IOException {
        if (!MainConfig.get().hot_reload_enabled) {
            sendError(exchange, 400, "Hot reload is disabled in config");
            return;
        }

        try {
            // Reload all custom trees from JSON files
            reloadAllCustomTrees();

            // Update all active replacers
            reloadAllReplacers();

            sendResponse(exchange, 200, "{\"status\": \"reloaded\"}");
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to hot-reload", e);
            sendError(exchange, 500, "Hot-reload failed: " + e.getMessage());
        }
    }

    private void reloadAllCustomTrees() {
        if (!MainConfig.get().hot_reload_enabled) return;

        Path treeDir = Paths.get("config", "tree_engine", "datapacks", "tree_engine_trees",
                                "data", "tree_engine", "worldgen", "configured_feature");

        try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(treeDir, "*.json")) {
            for (Path file : stream) {
                try {
                    String id = file.getFileName().toString().replace(".json", "");
                    String json = Files.readString(file);
                    JsonElement jsonElement = JsonParser.parseString(json);

                    // Parse and register
                    RegistryOps<JsonElement> ops = RegistryOps.of(com.mojang.serialization.JsonOps.INSTANCE,
                        minecraftServer.getRegistryManager());
                    DataResult<ConfiguredFeature<?, ?>> result =
                        ConfiguredFeature.CODEC.parse(ops, jsonElement);
                    ConfiguredFeature<?, ?> feature = result.getOrThrow();

                    RegistryUtils.registerFeatureDirectly(minecraftServer, "tree_engine:" + id, feature);

                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Failed to reload tree: " + file, e);
                }
            }
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to scan tree directory", e);
        }
    }

    private void reloadAllReplacers() {
        if (!MainConfig.get().hot_reload_enabled) return;

        java.util.List<TreeReplacerManager.TreeReplacer> replacers = TreeReplacerManager.getAll();
        for (TreeReplacerManager.TreeReplacer replacer : replacers) {
            try {
                RegistryUtils.updateReplacerInRegistry(minecraftServer, replacer);
            } catch (Exception e) {
                TreeEngine.LOGGER.error("Failed to reload replacer: " + replacer.vanilla_tree_id, e);
            }
        }
    }

    private void sendError(HttpExchange exchange, int code, String message) throws IOException {
        String json = "{\"error\": \"" + message + "\"}";
        sendResponse(exchange, code, json);
    }
}
