package savage.btaf.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import savage.btaf.SavsBetterTreesAndFlora;
import savage.btaf.config.SavsConfig;
import savage.btaf.worldgen.SavsWorldGen;

public class BtfReloadCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("btf")
                .then(CommandManager.literal("reload")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(BtfReloadCommand::execute)
                )
        );
    }

    private static int execute(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        try {
            // Force reload config from disk
            var configHolder = AutoConfig.getConfigHolder(SavsConfig.class);
            configHolder.load();
            SavsBetterTreesAndFlora.CONFIG = configHolder.getConfig();
            
            // Reapply feature modifications
            SavsWorldGen.onServerStarted(source.getServer());
            
            source.sendFeedback(() -> Text.literal("§a[BTAF] Config reloaded and features updated!"), true);
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("§c[BTAF] Failed to reload: " + e.getMessage()));
            SavsBetterTreesAndFlora.LOGGER.error("Failed to reload", e);
            return 0;
        }
    }
}
