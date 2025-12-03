package savage.tree_engine.web;

import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.MainConfig;
import java.util.concurrent.*;

/**
 * Manages a dedicated thread pool for tree generation in PhantomWorld.
 * This executor is completely separate from Minecraft's world generation threads,
 * ensuring that tree preview generation doesn't block the main server thread.
 */
public class TreeGenerationExecutor {
    private static ExecutorService executor;
    
    /**
     * Initializes the tree generation executor with a configurable number of worker threads.
     * Thread count is read from MainConfig and clamped between 1-16 for safety.
     */
    public static void initialize() {
        // Get thread count from config (clamp between 1-16)
        int threadCount = Math.max(1, Math.min(16, MainConfig.get().tree_generation_threads));
        
        // Create a fixed thread pool with configurable worker threads
        // This is separate from Minecraft's world gen threads
        executor = Executors.newFixedThreadPool(
            threadCount,
            r -> {
                Thread thread = new Thread(r, "Tree-Engine-Worker");
                thread.setDaemon(true); // Don't prevent server shutdown
                return thread;
            }
        );
        TreeEngine.LOGGER.info("Initialized Tree Generation Executor with {} worker threads", threadCount);
    }
    
    /**
     * Shuts down the executor gracefully, waiting up to 5 seconds for tasks to complete.
     */
    public static void shutdown() {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
            TreeEngine.LOGGER.info("Shut down Tree Generation Executor");
        }
    }
    
    /**
     * Submits a task to the tree generation executor and returns a CompletableFuture.
     * 
     * @param task The task to execute
     * @return A CompletableFuture that will complete with the task result
     */
    public static <T> CompletableFuture<T> submit(Callable<T> task) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return task.call();
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executor);
    }
}
