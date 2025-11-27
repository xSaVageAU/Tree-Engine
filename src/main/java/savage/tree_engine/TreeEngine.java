package savage.tree_engine;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TreeEngine implements ModInitializer {
	public static final String MOD_ID = "tree_engine";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

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
            // Start web editor
            savage.tree_engine.web.WebEditorServer.start(server);
        });
        
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            savage.tree_engine.web.WebEditorServer.stop();
        });
    }
}
