package savage.btaf.worldgen;

import net.minecraft.block.Block;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import net.minecraft.world.gen.stateprovider.SimpleBlockStateProvider;
import savage.btaf.SavsBetterTreesAndFlora;
import savage.btaf.util.BlockParser;

public class SavsWorldGen {
    private static MinecraftServer server;

    public static void init() {
        // We'll use the mixin to trigger modification when server loads
        SavsBetterTreesAndFlora.LOGGER.info("SavsWorldGen initialized");
    }

    public static void onServerStarted(MinecraftServer server) {
        SavsBetterTreesAndFlora.LOGGER.info("Server started, modifying features...");
        modifyFeatures(server);
    }

    private static void modifyFeatures(MinecraftServer server) {
        try {
            var registryManager = server.getRegistryManager();
            var registry = registryManager.getOrThrow(RegistryKeys.CONFIGURED_FEATURE);
            
            // Parse blocks from config
            Block logBlock = BlockParser.parseBlock(
                SavsBetterTreesAndFlora.CONFIG.oak3Log,
                net.minecraft.block.Blocks.DARK_OAK_LOG
            );
            Block foliageBlock = BlockParser.parseBlock(
                SavsBetterTreesAndFlora.CONFIG.oak3Foliage,
                net.minecraft.block.Blocks.AZALEA_LEAVES
            );

            SavsBetterTreesAndFlora.LOGGER.info("Modifying oak_3 features: Log={}, Foliage={}", 
                logBlock, foliageBlock);

            // Find and modify oak_3 features
            modifyOak3Feature(registry, "savs_btf:oak_3", logBlock, foliageBlock);
            modifyOak3Feature(registry, "savs_btf:oak_3_bees_0002", logBlock, foliageBlock);
            modifyOak3Feature(registry, "savs_btf:oak_3_bees_002", logBlock, foliageBlock);
            modifyOak3Feature(registry, "savs_btf:oak_3_bees_005", logBlock, foliageBlock);

            SavsBetterTreesAndFlora.LOGGER.info("Successfully modified oak_3 features!");
        } catch (Exception e) {
            SavsBetterTreesAndFlora.LOGGER.error("Failed to modify features", e);
        }
    }

    private static void modifyOak3Feature(
        net.minecraft.registry.Registry<ConfiguredFeature<?, ?>> registry,
        String featureId,
        Block logBlock,
        Block foliageBlock
    ) {
        Identifier id = Identifier.tryParse(featureId);
        if (id == null) return;

        RegistryEntry.Reference<ConfiguredFeature<?, ?>> entry = registry.getEntry(id).orElse(null);
        if (entry == null) {
            SavsBetterTreesAndFlora.LOGGER.warn("Feature not found: {}", featureId);
            return;
        }

        ConfiguredFeature<?, ?> feature = entry.value();
        if (!(feature.config() instanceof TreeFeatureConfig treeConfig)) {
            SavsBetterTreesAndFlora.LOGGER.warn("Feature {} is not a tree feature", featureId);
            return;
        }

        // Use reflection to modify the final fields
        try {
            var trunkField = TreeFeatureConfig.class.getDeclaredField("trunkProvider");
            trunkField.setAccessible(true);
            trunkField.set(treeConfig, SimpleBlockStateProvider.of(logBlock));

            var foliageField = TreeFeatureConfig.class.getDeclaredField("foliageProvider");
            foliageField.setAccessible(true);
            foliageField.set(treeConfig, SimpleBlockStateProvider.of(foliageBlock));

            SavsBetterTreesAndFlora.LOGGER.info("Modified feature: {}", featureId);
        } catch (Exception e) {
            SavsBetterTreesAndFlora.LOGGER.error("Failed to modify feature {}", featureId, e);
        }
    }
}

