package savage.tree_engine.registry;

import net.fabricmc.fabric.api.event.registry.DynamicRegistrySetupCallback;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.intprovider.ConstantIntProvider;
import net.minecraft.util.math.intprovider.UniformIntProvider;
import net.minecraft.world.gen.feature.ConfiguredFeature;
import net.minecraft.world.gen.feature.Feature;
import net.minecraft.world.gen.feature.TreeFeatureConfig;
import net.minecraft.world.gen.foliage.BlobFoliagePlacer;
import net.minecraft.world.gen.stateprovider.BlockStateProvider;
import net.minecraft.world.gen.trunk.StraightTrunkPlacer;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.TreeConfigManager;
import savage.tree_engine.config.TreeWrapper;

import java.util.Map;

public class VirtualDatapackManager {

    public static void init() {
        DynamicRegistrySetupCallback.EVENT.register(registryManager -> {
            registryManager.getOptional(RegistryKeys.CONFIGURED_FEATURE).ifPresent(registry -> {
                registerFeatures(registry);
            });
        });
    }

    private static void registerFeatures(Registry<ConfiguredFeature<?, ?>> registry) {
        TreeEngine.LOGGER.info("Injecting Virtual Datapack features...");

        Map<String, TreeWrapper> trees = TreeConfigManager.getTrees();
        
        for (Map.Entry<String, TreeWrapper> entry : trees.entrySet()) {
            String id = entry.getKey();
            TreeWrapper wrapper = entry.getValue();
            
            try {
                ConfiguredFeature<?, ?> feature = createTreeFeature(wrapper);
                Registry.register(registry, Identifier.of(TreeEngine.MOD_ID, id), feature);
                TreeEngine.LOGGER.info("Injected feature: " + TreeEngine.MOD_ID + ":" + id);
            } catch (Exception e) {
                TreeEngine.LOGGER.error("Failed to create feature for tree: " + id, e);
            }
        }
    }

    private static ConfiguredFeature<?, ?> createTreeFeature(TreeWrapper wrapper) {
        // Parse wrapper.config JSON using Minecraft's codec system
        com.mojang.serialization.DataResult<TreeFeatureConfig> result = TreeFeatureConfig.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, wrapper.config);
        TreeFeatureConfig config = result.getOrThrow(errorMsg -> new IllegalStateException("Failed to parse tree config: " + errorMsg));
        
        return new ConfiguredFeature<>(Feature.TREE, config);
    }
}
