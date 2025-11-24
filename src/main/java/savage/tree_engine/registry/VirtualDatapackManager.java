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
import savage.tree_engine.config.TreeDefinition;

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

        Map<String, TreeDefinition> trees = TreeConfigManager.getTrees();
        
        for (Map.Entry<String, TreeDefinition> entry : trees.entrySet()) {
            String id = entry.getKey();
            TreeDefinition def = entry.getValue();
            
            try {
                ConfiguredFeature<?, ?> feature = createTreeFeature(def);
                Registry.register(registry, Identifier.of(TreeEngine.MOD_ID, id), feature);
                TreeEngine.LOGGER.info("Injected feature: " + TreeEngine.MOD_ID + ":" + id);
            } catch (Exception e) {
                TreeEngine.LOGGER.error("Failed to create feature for tree: " + id, e);
            }
        }
    }

    private static ConfiguredFeature<?, ?> createTreeFeature(TreeDefinition def) {
        Block trunkBlock = Registries.BLOCK.get(Identifier.of(def.trunk_block.split(":")[0], def.trunk_block.split(":")[1]));
        Block foliageBlock = Registries.BLOCK.get(Identifier.of(def.foliage_block.split(":")[0], def.foliage_block.split(":")[1]));

        int heightMin = def.trunk_height_min != null ? def.trunk_height_min : 4;
        int heightMax = def.trunk_height_max != null ? def.trunk_height_max : 6;
        int radius = def.foliage_radius != null ? def.foliage_radius : 2;
        int offset = def.foliage_offset != null ? def.foliage_offset : 0;

        return new ConfiguredFeature<>(
            Feature.TREE,
            new TreeFeatureConfig.Builder(
                BlockStateProvider.of(trunkBlock),
                new StraightTrunkPlacer(heightMin, heightMax - heightMin, 0),
                BlockStateProvider.of(foliageBlock),
                new BlobFoliagePlacer(ConstantIntProvider.create(radius), ConstantIntProvider.create(offset), 3),
                new net.minecraft.world.gen.feature.size.TwoLayersFeatureSize(1, 0, 1)
            ).build()
        );
    }
}
