package savage.tree_engine.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.TreeConfigManager;
import savage.tree_engine.config.TreeDefinition;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TreeApiHandler implements HttpHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

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
                } else if ("import_vanilla".equals(endpoint)) {
                    if ("POST".equals(method)) handleImportVanilla(exchange);
                    else sendError(exchange, 405, "Method not allowed");
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

    private void handleImportVanilla(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            Map<String, String> body = GSON.fromJson(reader, Map.class);
            String id = body.get("id");
            
            if (id == null) {
                sendError(exchange, 400, "Missing 'id' field");
                return;
            }

            TreeDefinition def = TreeConfigManager.importVanillaTree(id);
            if (def == null) {
                sendError(exchange, 404, "Vanilla tree not found or invalid");
                return;
            }
            
            String json = GSON.toJson(def);
            sendResponse(exchange, 200, json);
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to import tree", e);
            sendError(exchange, 500, "Import failed: " + e.getMessage());
        }
    }

    private void handleList(HttpExchange exchange) throws IOException {
        Map<String, TreeDefinition> trees = TreeConfigManager.getTrees();
        List<TreeDefinition> list = new ArrayList<>(trees.values());
        String json = GSON.toJson(list);
        sendResponse(exchange, 200, json);
    }

    private void handleGet(HttpExchange exchange, String id) throws IOException {
        TreeDefinition tree = TreeConfigManager.getTree(id);
        if (tree == null) {
            sendError(exchange, 404, "Tree not found");
            return;
        }
        String json = GSON.toJson(tree);
        sendResponse(exchange, 200, json);
    }

    private void handleSave(HttpExchange exchange) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            TreeDefinition tree = GSON.fromJson(reader, TreeDefinition.class);
            
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
