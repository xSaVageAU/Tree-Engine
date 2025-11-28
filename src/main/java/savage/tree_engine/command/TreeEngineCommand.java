package savage.tree_engine.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.DataResult;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.TreeReplacerManager;
import savage.tree_engine.util.RegistryUtils;
import savage.tree_engine.web.WebEditorServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

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
                    .executes(TreeEngineCommand::reloadTrees)
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
            () -> Text.literal("§f/tree_engine reload §7- Hot-reload all trees and replacers"),
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
    
    private static int reloadTrees(CommandContext<ServerCommandSource> context) {
        try {
            var server = context.getSource().getServer();

            // Reload all custom trees from JSON files
            reloadAllCustomTrees(server);

            // Update all active replacers
            reloadAllReplacers(server);

            context.getSource().sendFeedback(
                () -> Text.literal("§aTree Engine: All trees and replacers hot-reloaded!"),
                true
            );
            return 1;
        } catch (Exception e) {
            context.getSource().sendError(
                Text.literal("§cTree Engine: Reload failed: " + e.getMessage())
            );
            return 0;
        }
    }

    private static void reloadAllCustomTrees(net.minecraft.server.MinecraftServer server) {
        Path treeDir = Paths.get("config", "tree_engine", "datapacks", "tree_engine_trees",
                                "data", "tree_engine", "worldgen", "configured_feature");

        try (Stream<Path> files = Files.list(treeDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                 .forEach(file -> {
                     try {
                         String id = file.getFileName().toString().replace(".json", "");
                         String json = Files.readString(file);
                         JsonElement jsonElement = JsonParser.parseString(json);

                         // Parse and register
                         RegistryOps<JsonElement> ops = RegistryOps.of(com.mojang.serialization.JsonOps.INSTANCE,
                             server.getRegistryManager());
                         DataResult<ConfiguredFeature<?, ?>> result =
                             ConfiguredFeature.CODEC.parse(ops, jsonElement);
                         ConfiguredFeature<?, ?> feature = result.getOrThrow();

                         RegistryUtils.registerFeatureDirectly(server, "tree_engine:" + id, feature);

                     } catch (Exception e) {
                         TreeEngine.LOGGER.error("Failed to reload tree: " + file, e);
                     }
                 });
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to scan tree directory", e);
        }
    }

    private static void reloadAllReplacers(net.minecraft.server.MinecraftServer server) {
        List<TreeReplacerManager.TreeReplacer> replacers = TreeReplacerManager.getAll();
        for (TreeReplacerManager.TreeReplacer replacer : replacers) {
            try {
                RegistryUtils.updateReplacerInRegistry(server, replacer);
            } catch (Exception e) {
                TreeEngine.LOGGER.error("Failed to reload replacer: " + replacer.vanilla_tree_id, e);
            }
        }
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
