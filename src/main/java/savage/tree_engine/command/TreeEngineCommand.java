package savage.tree_engine.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import savage.tree_engine.web.WebEditorServer;

public class TreeEngineCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("tree_engine")
                .executes(TreeEngineCommand::help)  // Default to help if no args
                .then(CommandManager.literal("help")
                    .executes(TreeEngineCommand::help)
                )
                .then(CommandManager.literal("reload")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(TreeEngineCommand::reload)
                )
        );
    }
    
    private static int help(CommandContext<ServerCommandSource> context) {
        int port = savage.tree_engine.config.MainConfig.get().server_port;
        
        context.getSource().sendFeedback(
            () -> Text.literal("§6§l=== Tree Engine ==="),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§eWeb Editor: §fhttp://localhost:" + port),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§7Open this URL in your browser to edit trees"),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal(""),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§eCommands:"),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§f/tree_engine help §7- Show this help message"),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§f/tree_engine reload §7- Reload web server"),
            false
        );
        return 1;
    }
    
    private static int reload(CommandContext<ServerCommandSource> context) {
        WebEditorServer.reload();
        context.getSource().sendFeedback(
            () -> Text.literal("§aWeb Editor Server reloaded! Refresh your browser."),
            true
        );
        return 1;
    }
}
