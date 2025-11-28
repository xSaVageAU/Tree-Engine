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
import savage.tree_engine.web.PathValidator;

public class WebEditorServer {
    private static HttpServer server;
    private static MinecraftServer minecraftServer;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static void setMinecraftServer(MinecraftServer mcServer) {
        minecraftServer = mcServer;
    }

    public static void start(MinecraftServer mcServer) {
        if (server != null) {
            TreeEngine.LOGGER.warn("Web Editor Server is already running!");
            return;
        }
        
        minecraftServer = mcServer;
        exportWebFiles();
        startServer();
    }
    
    public static boolean isRunning() {
        return server != null;
    }
    
    public static int getPort() {
        return savage.tree_engine.config.MainConfig.get().server_port;
    }
    
    public static void reload() {
        if (server == null) {
            TreeEngine.LOGGER.warn("Cannot reload: Web Editor Server is not running");
            return;
        }
        
        TreeEngine.LOGGER.info("Reloading Web Editor Server...");
        stop();
        startServer();
        TreeEngine.LOGGER.info("Web Editor Server reloaded");
    }
    
    private static void exportWebFiles() {
        Path webDir = savage.tree_engine.config.MainConfig.getWebDir();
        WebFileExporter.exportFiles(webDir);
    }
    
    private static void startServer() {
        try {
            // Initialize authentication system
            AuthenticationManager.initialize();
            
            int port = savage.tree_engine.config.MainConfig.get().server_port;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
            
            // Static files - no authentication required
            server.createContext("/", new StaticHandler());
            server.createContext("/textures/", new TextureHandler());
            
            // API endpoints - authentication required
            server.createContext("/api/generate", new AuthFilter(new GenerateHandler()));
            server.createContext("/api/texture-packs", new AuthFilter(new TexturePacksHandler()));
            server.createContext("/api/", new AuthFilter(new TreeApiHandler(minecraftServer)));
            
            server.setExecutor(null); // creates a default executor
            server.start();
            TreeEngine.LOGGER.info("Web Editor Server started on port " + port);
            
            if (savage.tree_engine.config.MainConfig.get().open_browser_on_start) {
                try {
                    net.minecraft.util.Util.getOperatingSystem().open("http://localhost:" + port);
                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Failed to open browser", e);
                }
            }
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to start Web Editor Server", e);
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null; // Clear the reference so isRunning() returns false
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
            
            // Check for path traversal attempts
            if (PathValidator.containsTraversalSequence(path)) {
                TreeEngine.LOGGER.warn("Path traversal attempt detected in static file request: {}", path);
                send404(t);
                return;
            }
            
            Path webDir;
            if (savage.tree_engine.config.MainConfig.get().dev_mode_enabled) {
                String sourcePath = savage.tree_engine.config.MainConfig.get().source_path;
                if (sourcePath != null && !sourcePath.isEmpty()) {
                    webDir = Path.of(sourcePath);
                } else {
                    webDir = savage.tree_engine.config.MainConfig.getWebDir();
                    TreeEngine.LOGGER.warn("Dev mode enabled but source_path is empty, falling back to internal web dir");
                }
            } else {
                webDir = savage.tree_engine.config.MainConfig.getWebDir();
            }
            
            // Resolve path safely
            String requestedFile = path.substring(1); // Remove leading /
            Path file;
            try {
                // Use resolveSafeRelativePath to allow subdirectories (css/main.css)
                file = PathValidator.resolveSafeRelativePath(requestedFile, webDir);
            } catch (SecurityException e) {
                TreeEngine.LOGGER.warn("Security violation in static file request: {}", path);
                send404(t);
                return;
            }
            
            if (!java.nio.file.Files.exists(file) || java.nio.file.Files.isDirectory(file)) {
                send404(t);
                return;
            }
            
            String mimeType = "text/html";
            if (path.endsWith(".css")) mimeType = "text/css";
            else if (path.endsWith(".js")) mimeType = "application/javascript";
            else if (path.endsWith(".png")) mimeType = "image/png";
            
            byte[] bytes = java.nio.file.Files.readAllBytes(file);
            
            // Security headers
            t.getResponseHeaders().set("Content-Type", mimeType);
            t.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            t.getResponseHeaders().set("X-Frame-Options", "DENY");
            t.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
            
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
                    // Parse Request - raw Minecraft JSON config (full feature)
                    InputStreamReader reader = new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8);
                    com.google.gson.JsonElement json = com.google.gson.JsonParser.parseReader(reader);

                    // Parse using RegistryOps to handle full ConfiguredFeature
                    net.minecraft.registry.RegistryOps<com.google.gson.JsonElement> ops = net.minecraft.registry.RegistryOps.of(com.mojang.serialization.JsonOps.INSTANCE, minecraftServer.getRegistryManager());
                    com.mojang.serialization.DataResult<ConfiguredFeature<?, ?>> result = ConfiguredFeature.CODEC.parse(ops, json);
                    
                    ConfiguredFeature<?, ?> feature = result.getOrThrow(s -> new RuntimeException("Failed to parse feature: " + s));

                    // Schedule generation on main thread
                    CompletableFuture<List<BlockInfo>> future = CompletableFuture.supplyAsync(() -> {
                        return generateTree(feature);
                    }, minecraftServer);

                    List<BlockInfo> blocks = future.join();
                    String jsonResponse = GSON.toJson(blocks.stream().map(BlockInfo::toJson).toList());

                    // Send Response
                    t.getResponseHeaders().set("Content-Type", "application/json");
                    t.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); // CORS for dev
                    t.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
                    t.getResponseHeaders().set("X-Frame-Options", "DENY");
                    t.getResponseHeaders().set("X-XSS-Protection", "1; mode=block");
                    
                    byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
                    t.sendResponseHeaders(200, bytes.length);
                    OutputStream os = t.getResponseBody();
                    os.write(bytes);
                    os.close();

                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Tree generation failed", e);
                    String error = "{\"error\": \"Failed to generate tree\"}";
                    t.sendResponseHeaders(500, error.length());
                    OutputStream os = t.getResponseBody();
                    os.write(error.getBytes());
                    os.close();
                }
            } else {
                t.sendResponseHeaders(405, -1); // Method Not Allowed
            }
        }

        private List<BlockInfo> generateTree(ConfiguredFeature<?, ?> feature) {
            PhantomWorld world = new PhantomWorld(minecraftServer.getRegistryManager(), minecraftServer);

            // Generate the feature directly
            // This handles all wrappers (random_patch, selectors, etc.) automatically
            feature.generate(world, world.getChunkGenerator(), Random.create(), new BlockPos(0, 0, 0));

            return world.getPlacedBlocks();
        }
    }
}
