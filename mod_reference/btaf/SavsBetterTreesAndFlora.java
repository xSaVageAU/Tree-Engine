package savage.btaf;

import me.shedaniel.autoconfig.AutoConfig;
import me.shedaniel.autoconfig.serializer.GsonConfigSerializer;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.btaf.config.SavsConfig;

public class SavsBetterTreesAndFlora implements ModInitializer {
	public static final String MOD_ID = "savs-btaf";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static SavsConfig CONFIG;

	@Override
	public void onInitialize() {
        AutoConfig.register(SavsConfig.class, GsonConfigSerializer::new);
        CONFIG = AutoConfig.getConfigHolder(SavsConfig.class).getConfig();

        savage.btaf.worldgen.SavsWorldGen.init();

        // Register command
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> 
                savage.btaf.command.BtfReloadCommand.register(dispatcher)
        );

		LOGGER.info("Savs Better Trees & Flora initialized with config!");
	}
}