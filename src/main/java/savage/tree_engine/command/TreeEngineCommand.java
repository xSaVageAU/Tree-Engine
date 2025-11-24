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
                .then(CommandManager.literal("reload")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(TreeEngineCommand::reload)
                )
        );
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
