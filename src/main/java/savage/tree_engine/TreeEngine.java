package savage.tree_engine;

import net.fabricmc.api.ModInitializer;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TreeEngine implements ModInitializer {
	public static final String MOD_ID = "tree_engine";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	// Registry tracking for hot reloading
	public static final Map<String, ConfiguredFeature<?, ?>> customTrees = new ConcurrentHashMap<>();
	public static final Map<String, savage.tree_engine.config.TreeReplacerManager.TreeReplacer> activeReplacers = new ConcurrentHashMap<>();

	@Override
    public void onInitialize() {
        LOGGER.info("Initializing Tree Engine");
        
        // Initialize main config first
        savage.tree_engine.config.MainConfig.init();
        
        // Initialize tree replacers
        savage.tree_engine.config.TreeReplacerManager.init();
        
        // Register commands
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            savage.tree_engine.command.TreeEngineCommand.register(dispatcher);
        });
        
        // Register server lifecycle events
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            // Store server reference for web editor (doesn't auto-start)
            savage.tree_engine.web.WebEditorServer.setMinecraftServer(server);
        });
        
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            // Auto-stop web server on shutdown for cleanup
            savage.tree_engine.web.WebEditorServer.stop();
        });
    }
}
