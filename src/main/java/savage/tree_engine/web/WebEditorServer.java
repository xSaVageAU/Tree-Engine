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
import savage.tree_engine.config.TreeDefinition;
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
            server.createContext("/api/", new TreeApiHandler());
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
                    // Parse Request
                    InputStreamReader reader = new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8);
                    TreeDefinition def = GSON.fromJson(reader, TreeDefinition.class);

                    // Schedule generation on main thread
                    CompletableFuture<List<BlockInfo>> future = CompletableFuture.supplyAsync(() -> {
                        return generateTree(def);
                    }, minecraftServer);

                    List<BlockInfo> blocks = future.join();
                    String jsonResponse = GSON.toJson(blocks);

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

        private List<BlockInfo> generateTree(TreeDefinition def) {
            PhantomWorld world = new PhantomWorld(minecraftServer.getRegistryManager());
            
            // Reconstruct feature from definition (Duplicate logic from VirtualDatapackManager, refactor later?)
            // For now, let's just duplicate for speed, or better, make a shared utility.
            // Let's duplicate for now to avoid circular deps or complex refactors in this step.
            
            Block trunkBlock = Registries.BLOCK.get(Identifier.of(def.trunk_block.split(":")[0], def.trunk_block.split(":")[1]));
            Block foliageBlock = Registries.BLOCK.get(Identifier.of(def.foliage_block.split(":")[0], def.foliage_block.split(":")[1]));

            int heightMin = def.trunk_height_min != null ? def.trunk_height_min : 4;
            int heightMax = def.trunk_height_max != null ? def.trunk_height_max : 6;
            int radius = def.foliage_radius != null ? def.foliage_radius : 2;
            int offset = def.foliage_offset != null ? def.foliage_offset : 0;
            int foliageHeight = def.foliage_height != null ? def.foliage_height : 3;

            ConfiguredFeature<?, ?> feature = new ConfiguredFeature<>(
                Feature.TREE,
                new TreeFeatureConfig.Builder(
                    BlockStateProvider.of(trunkBlock),
                    PlacerFactory.createTrunkPlacer(def.trunk_placer_type, heightMin, heightMax),
                    BlockStateProvider.of(foliageBlock),
                    PlacerFactory.createFoliagePlacer(def.foliage_placer_type, radius, offset, foliageHeight),
                    new net.minecraft.world.gen.feature.size.TwoLayersFeatureSize(1, 0, 1)
                ).build()
            );

            // Generate at 0,0,0
            feature.generate(world, null, Random.create(), new BlockPos(0, 0, 0));

            return world.getPlacedBlocks();
        }
    }
}
