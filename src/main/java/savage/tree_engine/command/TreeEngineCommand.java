package savage.tree_engine.command;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.web.WebEditorServer;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class TreeEngineCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("tree_engine")
                .then(CommandManager.literal("reload")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(TreeEngineCommand::reload)
                )
                .then(CommandManager.literal("place")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("tree", StringArgumentType.string())
                        .executes(TreeEngineCommand::placeTree)
                    )
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

    private static int placeTree(CommandContext<ServerCommandSource> context) {
        String treeName = StringArgumentType.getString(context, "tree");
        ServerCommandSource source = context.getSource();
        BlockPos pos = BlockPos.ofFloored(source.getPosition());

        try {
            Path file = Paths.get("config", "tree_engine", "datapacks", "your_pack", "data", "tree_engine", "worldgen", "configured_feature", treeName + ".json");
            if (!Files.exists(file)) {
                source.sendError(Text.literal("Tree '" + treeName + "' not found"));
                return 0;
            }

            try (Reader reader = Files.newBufferedReader(file)) {
                JsonElement json = JsonParser.parseReader(reader);
                JsonElement configJson = json.getAsJsonObject().get("config");

                DataResult<TreeFeatureConfig> result = TreeFeatureConfig.CODEC.parse(JsonOps.INSTANCE, configJson);
                TreeFeatureConfig config = result.getOrThrow(s -> new RuntimeException("Failed to parse tree config: " + s));

                ConfiguredFeature<?, ?> configuredFeature = new ConfiguredFeature<>(Feature.TREE, config);

                // Generate the tree
                configuredFeature.generate(source.getWorld(), source.getWorld().getChunkManager().getChunkGenerator(), Random.create(), pos);

                source.sendFeedback(() -> Text.literal("Placed tree '" + treeName + "' at " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ()), true);
                return 1;
            }
        } catch (Exception e) {
            TreeEngine.LOGGER.error("Failed to place tree: " + treeName, e);
            source.sendError(Text.literal("Failed to place tree: " + e.getMessage()));
            return 0;
        }
    }
}
