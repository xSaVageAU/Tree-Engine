package savage.tree_engine;

import net.fabricmc.api.ModInitializer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TreeEngine implements ModInitializer {
	public static final String MOD_ID = "tree_engine";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
    public void onInitialize() {
        LOGGER.info("Initializing Tree Engine");
        
        // Initialize main config first
        savage.tree_engine.config.MainConfig.init(); // Assuming MainConfig is in savage.tree_engine.config
        
        // Load tree configs
        savage.tree_engine.config.TreeConfigManager.load(); // Keeping FQN as per original context
        
        // Register virtual datapack
        savage.tree_engine.registry.VirtualDatapackManager.init(); // Keeping FQN as per original context
        
        // Register commands
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            savage.tree_engine.command.TreeEngineCommand.register(dispatcher);
        });
        
        // Register server lifecycle events for web editor
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTING.register(server -> { // Keeping FQN as per original context
            savage.tree_engine.web.WebEditorServer.start(server); // Keeping FQN as per original context
        });
        
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING.register(server -> { // Keeping FQN as per original context
            savage.tree_engine.web.WebEditorServer.stop(); // Keeping FQN as per original context
        });
    }
}