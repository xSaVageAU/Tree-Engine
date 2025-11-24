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
            // Export index.html from resources to config/tree_engine/web/
            java.io.InputStream is = WebEditorServer.class.getClassLoader().getResourceAsStream("web/index.html");
            if (is != null) {
                byte[] htmlBytes = is.readAllBytes();
                is.close();
                
                Path htmlFile = savage.tree_engine.config.MainConfig.getWebDir().resolve("index.html");
                java.nio.file.Files.write(htmlFile, htmlBytes);
                TreeEngine.LOGGER.info("Exported web files to: " + htmlFile.toAbsolutePath());
            }
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to export web files", e);
        }
    }
    
    private static void startServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(3000), 0);
            server.createContext("/", new StaticHandler());
            server.createContext("/api/generate", new GenerateHandler());
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
            try {
                // Load HTML from exported file
                Path htmlFile = savage.tree_engine.config.MainConfig.getWebDir().resolve("index.html");
                
                if (!java.nio.file.Files.exists(htmlFile)) {
                    String error = "Web interface not found";
                    t.sendResponseHeaders(404, error.length());
                    OutputStream os = t.getResponseBody();
                    os.write(error.getBytes());
                    os.close();
                    return;
                }
                
                byte[] htmlBytes = java.nio.file.Files.readAllBytes(htmlFile);
                
                t.getResponseHeaders().set("Content-Type", "text/html");
                t.sendResponseHeaders(200, htmlBytes.length);
                OutputStream os = t.getResponseBody();
                os.write(htmlBytes);
                os.close();
            } catch (Exception e) {
                TreeEngine.LOGGER.error("Error serving web interface", e);
                String error = "Internal server error";
                t.sendResponseHeaders(500, error.length());
                OutputStream os = t.getResponseBody();
                os.write(error.getBytes());
                os.close();
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

            ConfiguredFeature<?, ?> feature = new ConfiguredFeature<>(
                Feature.TREE,
                new TreeFeatureConfig.Builder(
                    BlockStateProvider.of(trunkBlock),
                    new StraightTrunkPlacer(heightMin, heightMax - heightMin, 0),
                    BlockStateProvider.of(foliageBlock),
                    new BlobFoliagePlacer(ConstantIntProvider.create(radius), ConstantIntProvider.create(offset), 3),
                    new net.minecraft.world.gen.feature.size.TwoLayersFeatureSize(1, 0, 1)
                ).build()
            );

            // Generate at 0,0,0
            feature.generate(world, null, Random.create(), new BlockPos(0, 0, 0));

            return world.getPlacedBlocks();
        }
    }
}
