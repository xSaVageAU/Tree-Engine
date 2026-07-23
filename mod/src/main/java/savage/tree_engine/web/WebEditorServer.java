package savage.tree_engine.web;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.world.PhantomWorld;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

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
    
    private static void startServer() {
        try {
            // Initialize authentication system
            AuthenticationManager.initialize();

            int port = savage.tree_engine.config.MainConfig.get().server_port;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

            // API endpoints - authentication required. The mod is headless: the
            // Tree Engine desktop launcher (a separate Wails app) is the editor
            // UI and talks to these routes directly.
            server.createContext("/api/generate", new AuthFilter(new GenerateHandler()));
            server.createContext("/api/benchmark", new AuthFilter(new BenchmarkHandler()));
            server.createContext("/api/", new AuthFilter(new TreeApiHandler(minecraftServer)));

            server.setExecutor(null); // creates a default executor
            server.start();
            TreeEngine.LOGGER.info("Web Editor Server started on port " + port);
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

    private static void sendJsonError(HttpExchange t, int code, String message, String details) throws IOException {
        com.google.gson.JsonObject json = new com.google.gson.JsonObject();
        json.addProperty("error", message);
        if (details != null) {
            json.addProperty("details", details);
        }
        String response = GSON.toJson(json);
        
        t.getResponseHeaders().set("Content-Type", "application/json");
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        t.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = t.getResponseBody()) {
            os.write(bytes);
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

                    // Generate tree asynchronously on worker thread
                    TreeGenerationExecutor.submit(() -> {
                        return generateTree(feature);
                    }).thenAccept(blocks -> {
                        try {
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
                        } catch (IOException e) {
                            TreeEngine.LOGGER.error("Failed to send response", e);
                        }
                    }).exceptionally(ex -> {
                        try {
                            TreeEngine.LOGGER.error("Tree generation failed", ex);
                            String message = "Failed to generate tree";
                            String details = ex.getMessage();
                            
                            // Extract useful info from DataResult error
                            if (ex instanceof RuntimeException && ex.getMessage().startsWith("Failed to parse feature:")) {
                                message = "Invalid Tree Configuration";
                                details = ex.getMessage().substring("Failed to parse feature: ".length());
                            }
                            
                            WebEditorServer.sendJsonError(t, 500, message, details);
                        } catch (IOException e) {
                            TreeEngine.LOGGER.error("Failed to send error response", e);
                        }
                        return null;
                    });

                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Failed to parse feature", e);
                    String message = "Failed to parse tree configuration";
                    String details = e.getMessage();
                    
                    if (e instanceof RuntimeException && e.getMessage().startsWith("Failed to parse feature:")) {
                        message = "Invalid Tree Configuration";
                        details = e.getMessage().substring("Failed to parse feature: ".length());
                    }
                    
                    WebEditorServer.sendJsonError(t, 500, message, details);
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

    static class BenchmarkHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            if ("POST".equals(t.getRequestMethod())) {
                try {
                    InputStreamReader reader = new InputStreamReader(t.getRequestBody(), StandardCharsets.UTF_8);
                    com.google.gson.JsonObject request = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
                    
                    if (!request.has("feature")) {
                        WebEditorServer.sendJsonError(t, 400, "Missing 'feature' field", null);
                        return;
                    }

                    int iterations = request.has("iterations") ? request.get("iterations").getAsInt() : 1000;
                    if (iterations > 10000) iterations = 10000; // Cap at 10k to prevent abuse
                    if (iterations < 1) iterations = 1;

                    com.google.gson.JsonElement featureJson = request.get("feature");

                    // Parse feature
                    net.minecraft.registry.RegistryOps<com.google.gson.JsonElement> ops = net.minecraft.registry.RegistryOps.of(com.mojang.serialization.JsonOps.INSTANCE, minecraftServer.getRegistryManager());
                    com.mojang.serialization.DataResult<ConfiguredFeature<?, ?>> result = ConfiguredFeature.CODEC.parse(ops, featureJson);
                    ConfiguredFeature<?, ?> feature = result.getOrThrow(s -> new RuntimeException("Failed to parse feature: " + s));

                    // Run benchmark coordination in a separate thread (not in the worker pool)
                    // This allows the benchmark to submit work to the worker pool without deadlocking
                    final int finalIterations = iterations;
                    CompletableFuture.supplyAsync(() -> {
                        return runBenchmark(feature, finalIterations);
                    }).thenAccept(resultData -> {
                        try {
                            String jsonResponse = GSON.toJson(resultData);

                            t.getResponseHeaders().set("Content-Type", "application/json");
                            byte[] bytes = jsonResponse.getBytes(StandardCharsets.UTF_8);
                            t.sendResponseHeaders(200, bytes.length);
                            try (OutputStream os = t.getResponseBody()) {
                                os.write(bytes);
                            }
                        } catch (IOException e) {
                            TreeEngine.LOGGER.error("Failed to send response", e);
                        }
                    }).exceptionally(ex -> {
                        try {
                            TreeEngine.LOGGER.error("Benchmark failed", ex);
                            String message = "Benchmark failed";
                            String details = ex.getMessage();

                            if (ex instanceof RuntimeException && ex.getMessage().startsWith("Failed to parse feature:")) {
                                message = "Invalid Tree Configuration";
                                details = ex.getMessage().substring("Failed to parse feature: ".length());
                            }
                            
                            WebEditorServer.sendJsonError(t, 500, message, details);
                        } catch (IOException e) {
                            TreeEngine.LOGGER.error("Failed to send error response", e);
                        }
                        return null;
                    });

                } catch (Exception e) {
                    TreeEngine.LOGGER.error("Failed to parse feature", e);
                    String message = "Failed to parse tree configuration";
                    String details = e.getMessage();
                    
                    if (e instanceof RuntimeException && e.getMessage().startsWith("Failed to parse feature:")) {
                        message = "Invalid Tree Configuration";
                        details = e.getMessage().substring("Failed to parse feature: ".length());
                    }
                    
                    WebEditorServer.sendJsonError(t, 500, message, details);
                }
            } else {
                t.sendResponseHeaders(405, -1);
            }
        }

        private BenchmarkResult runBenchmark(ConfiguredFeature<?, ?> feature, int iterations) {
            // Warmup
            for (int i = 0; i < 50; i++) {
                PhantomWorld world = new PhantomWorld(minecraftServer.getRegistryManager(), minecraftServer);
                feature.generate(world, world.getChunkGenerator(), Random.create(), new BlockPos(0, 0, 0));
            }

            // Measurement with parallel execution
            long startTime = System.nanoTime();
            
            // Process trees in parallel batches
            int batchSize = 100;
            for (int i = 0; i < iterations; i += batchSize) {
                int remaining = Math.min(batchSize, iterations - i);
                
                // Create futures for this batch
                @SuppressWarnings("unchecked")
                CompletableFuture<Void>[] futures = new CompletableFuture[remaining];
                for (int j = 0; j < remaining; j++) {
                    futures[j] = TreeGenerationExecutor.submit(() -> {
                        PhantomWorld world = new PhantomWorld(minecraftServer.getRegistryManager(), minecraftServer);
                        feature.generate(world, world.getChunkGenerator(), Random.create(), new BlockPos(0, 0, 0));
                        return null;
                    });
                }
                
                // Wait for batch to complete
                CompletableFuture.allOf(futures).join();
            }
            
            long endTime = System.nanoTime();
            long totalTimeNs = endTime - startTime;
            
            double totalTimeMs = totalTimeNs / 1_000_000.0;
            double avgTimeMs = totalTimeMs / iterations;
            double treesPerSecond = iterations / (totalTimeMs / 1000.0);

            return new BenchmarkResult(totalTimeMs, avgTimeMs, treesPerSecond, iterations);
        }

        }
        
        private static class BenchmarkResult {
            double totalTimeMs;
            double avgTimeMs;
            double treesPerSecond;
            int iterations;

            public BenchmarkResult(double totalTimeMs, double avgTimeMs, double treesPerSecond, int iterations) {
                this.totalTimeMs = totalTimeMs;
                this.avgTimeMs = avgTimeMs;
                this.treesPerSecond = treesPerSecond;
                this.iterations = iterations;
            }
        }
}
