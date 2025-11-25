package savage.tree_engine.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.intprovider.ConstantIntProvider;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import net.minecraft.world.gen.foliage.BlobFoliagePlacer;
import net.minecraft.world.gen.stateprovider.BlockStateProvider;
import net.minecraft.world.gen.trunk.StraightTrunkPlacer;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.world.PhantomWorld;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class WebEditorServer {
    private static HttpServer server;
    private static MinecraftServer minecraftServer;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void start(MinecraftServer mcServer) {
        minecraftServer = mcServer;
        exportWebFiles();
        startServer();
    }
    
    public static void reload() {
        TreeEngine.LOGGER.info("Reloading Web Editor Server...");
        stop();
        startServer();
        TreeEngine.LOGGER.info("Web Editor Server reloaded");
    }
    
    private static void exportWebFiles() {
        try {
            Path webDir = savage.tree_engine.config.MainConfig.getWebDir();
            java.nio.file.Files.createDirectories(webDir);
            
            // List of files to export
            String[] files = {
                "index.html",
                "style.css",
                "main.js",
                "schema-form.js",
                "tree-browser.js"
            };
            
            for (String fileName : files) {
                try (java.io.InputStream is = WebEditorServer.class.getClassLoader().getResourceAsStream("web/" + fileName)) {
                    if (is != null) {
                        Path target = webDir.resolve(fileName);
                        java.nio.file.Files.copy(is, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            TreeEngine.LOGGER.info("Exported web files to: " + webDir.toAbsolutePath());
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to export web files", e);
        }
    }
    
    private static void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(3000), 0);
            server.createContext("/", new StaticHandler());
            server.createContext("/api/generate", new GenerateHandler());
            server.createContext("/api/", new TreeApiHandler(minecraftServer));
            server.createContext("/textures/", new TextureHandler());
            server.setExecutor(null); // creates a default executor
            server.start();
            TreeEngine.LOGGER.info("Web Editor Server started on port 3000");
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to start Web Editor Server", e);
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            TreeEngine.LOGGER.info("Web Editor Server stopped");
        }
    }

    static class StaticHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            String path = t.getRequestURI().getPath();
            if (path.equals("/")) {
                path = "/index.html";
            }
            
            // Prevent directory traversal
            if (path.contains("..")) {
                send404(t);
                return;
            }
            
            Path webDir = savage.tree_engine.config.MainConfig.getWebDir();
            Path file = webDir.resolve(path.substring(1)); // Remove leading /
            
            if (!java.nio.file.Files.exists(file) || java.nio.file.Files.isDirectory(file)) {
                send404(t);
                return;
            }
            
            String mimeType = "text/html";
            if (path.endsWith(".css")) mimeType = "text/css";
            else if (path.endsWith(".js")) mimeType = "application/javascript";
            else if (path.endsWith(".png")) mimeType = "image/png";
            
            byte[] bytes = java.nio.file.Files.readAllBytes(file);
            
            t.getResponseHeaders().set("Content-Type", mimeType);
            t.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = t.getResponseBody()) {
                os.write(bytes);
            }
        }
        
        private void send404(HttpExchange t) throws IOException {
            String response = "404 Not Found";
            t.sendResponseHeaders(404, response.length());
            try (OutputStream os = t.getResponseBody()) {
                os.write(response.getBytes());
            }
        }
    }

    static class GenerateHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                try {
                    // Parse Request - raw Minecraft JSON config
                    InputStreamReader reader = new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8);
                    com.google.gson.JsonElement json = com.google.gson.JsonParser.parseReader(reader);

                    // Parse using JsonOps
                    com.mojang.serialization.DataResult<TreeFeatureConfig> result = TreeFeatureConfig.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, json);
                    TreeFeatureConfig config = result.getOrThrow(s -> new RuntimeException("Failed to parse tree config: " + s));

                    // Schedule generation on main thread
                    CompletableFuture<List<BlockInfo>> future = CompletableFuture.supplyAsync(() -> {
                        return generateTree(config);
                    }, minecraftServer);

                    List<BlockInfo> blocks = future.join();
                    String jsonResponse = GSON.toJson(blocks.stream().map(BlockInfo::toJson).toList());

                    // Send Response
                    t.getResponseHeaders().set("Content-Type", "application/json");
                    t.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // CORS for dev
                    byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
                    t.sendResponseHeaders(200, bytes.length);
                    OutputStream os = t.getResponseBody();
                    os.write(bytes);
                    os.close();

                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Error generating tree", e);
                    String error = "{\"error\": \"" + e.getMessage() + "\"}";
                    t.sendResponseHeaders(500, error.length());
                    OutputStream os = t.getResponseBody();
                    os.write(error.getBytes());
                    os.close();
                }
            } else {
                t.sendResponseHeaders(405, -1); // Method Not Allowed
            }
        }

        private List<BlockInfo> generateTree(TreeFeatureConfig config) {
            PhantomWorld world = new PhantomWorld(minecraftServer.getRegistryManager(), minecraftServer);

            // Use Feature.TREE directly to avoid ConfiguredFeature wrapper issues
            // We need to cast to Feature<TreeFeatureConfig> to call generate with config
            ((Feature<TreeFeatureConfig>) Feature.TREE).generate(new net.minecraft.world.gen.feature.util.FeatureContext<>(
                null, // Optional<ConfiguredFeature<?, ?>>
                world,
                world.getChunkGenerator(), // ChunkGenerator
                Random.create(),
                new BlockPos(0, 0, 0),
                config
            ));

            return world.getPlacedBlocks();
        }
    }
}
