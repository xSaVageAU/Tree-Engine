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
                .then(CommandManager.literal("web")
                    .then(CommandManager.literal("start")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(TreeEngineCommand::webStart)
                    )
                    .then(CommandManager.literal("stop")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(TreeEngineCommand::webStop)
                    )
                    .then(CommandManager.literal("reload")
                        .requires(source -> source.hasPermissionLevel(2))
                        .executes(TreeEngineCommand::webReload)
                    )
                    .then(CommandManager.literal("status")
                        .executes(TreeEngineCommand::webStatus)
                    )
                )
                .then(CommandManager.literal("reload")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(TreeEngineCommand::webReload)  // Alias for web reload
                )
        );
    }
    
    private static int help(CommandContext<ServerCommandSource> context) {
        int port = savage.tree_engine.config.MainConfig.get().server_port;
        boolean running = WebEditorServer.isRunning();
        
        context.getSource().sendFeedback(
            () -> Text.literal("§6§l=== Tree Engine ==="),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§eWeb Editor: §fhttp://localhost:" + port + (running ? " §a(Running)" : " §c(Stopped)")),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§7Use §f/tree_engine web start §7to launch the editor"),
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
            () -> Text.literal("§f/tree_engine web start §7- Start the web editor"),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§f/tree_engine web stop §7- Stop the web editor"),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§f/tree_engine web reload §7- Reload the web server"),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§f/tree_engine web status §7- Check server status"),
            false
        );
        return 1;
    }
    
    private static int webStart(CommandContext<ServerCommandSource> context) {
        if (WebEditorServer.isRunning()) {
            context.getSource().sendFeedback(
                () -> Text.literal("§eWeb Editor Server is already running on port " + WebEditorServer.getPort()),
                false
            );
            return 0;
        }
        
        try {
            WebEditorServer.start(context.getSource().getServer());
            int port = WebEditorServer.getPort();
            context.getSource().sendFeedback(
                () -> Text.literal("§aWeb Editor Server started on port " + port),
                true
            );
            context.getSource().sendFeedback(
                () -> Text.literal("§7Open §fhttp://localhost:" + port + " §7in your browser"),
                false
            );
            return 1;
        } catch (Exception e) {
            context.getSource().sendFeedback(
                () -> Text.literal("§cFailed to start Web Editor Server: " + e.getMessage()),
                false
            );
            return 0;
        }
    }
    
    private static int webStop(CommandContext<ServerCommandSource> context) {
        if (!WebEditorServer.isRunning()) {
            context.getSource().sendFeedback(
                () -> Text.literal("§eWeb Editor Server is not running"),
                false
            );
            return 0;
        }
        
        WebEditorServer.stop();
        context.getSource().sendFeedback(
            () -> Text.literal("§aWeb Editor Server stopped"),
            true
        );
        return 1;
    }
    
    private static int webReload(CommandContext<ServerCommandSource> context) {
        if (!WebEditorServer.isRunning()) {
            context.getSource().sendFeedback(
                () -> Text.literal("§eWeb Editor Server is not running. Use §f/tree_engine web start §eto start it."),
                false
            );
            return 0;
        }
        
        WebEditorServer.reload();
        context.getSource().sendFeedback(
            () -> Text.literal("§aWeb Editor Server reloaded! Refresh your browser."),
            true
        );
        return 1;
    }
    
    private static int webStatus(CommandContext<ServerCommandSource> context) {
        int port = WebEditorServer.getPort();
        boolean running = WebEditorServer.isRunning();
        
        context.getSource().sendFeedback(
            () -> Text.literal("§6=== Web Editor Status ==="),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§eStatus: " + (running ? "§aRunning" : "§cStopped")),
            false
        );
        context.getSource().sendFeedback(
            () -> Text.literal("§ePort: §f" + port),
            false
        );
        if (running) {
            context.getSource().sendFeedback(
                () -> Text.literal("§eURL: §fhttp://localhost:" + port),
                false
            );
        }
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
