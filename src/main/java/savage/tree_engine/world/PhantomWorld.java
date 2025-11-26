package savage.tree_engine.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.StructureWorldAccess;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.tick.QueryableTickScheduler;
import org.jetbrains.annotations.Nullable;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.web.BlockInfo;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.biome.source.FixedBiomeSource;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.carver.CarverContext;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.VerticalBlockSample;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.ChunkRegion;
import net.minecraft.world.biome.source.BiomeAccess;
import java.util.concurrent.CompletableFuture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public class PhantomWorld implements StructureWorldAccess {
    private final List<BlockInfo> placedBlocks = new ArrayList<>();
    private final Map<BlockPos, BlockState> blockStates = new HashMap<>();
    private final DynamicRegistryManager registryManager;
    private final Random random;
    private final MinecraftServer server;

    public PhantomWorld(DynamicRegistryManager registryManager, MinecraftServer server) {
        this.registryManager = registryManager;
        this.random = Random.create();
        this.server = server;
    }

    public List<BlockInfo> getPlacedBlocks() {
        // Return unique blocks from the map to avoid z-fighting
        // The map always contains the latest block at each position
        List<BlockInfo> uniqueBlocks = new ArrayList<>();
        for (Map.Entry<BlockPos, BlockState> entry : blockStates.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            uniqueBlocks.add(new BlockInfo(pos.getX(), pos.getY(), pos.getZ(), state));
        }
        return uniqueBlocks;
    }

    @Override
    public boolean setBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth) {
        // CRITICAL: Create an immutable copy of the position
        // Tree generators often use BlockPos.Mutable and modify it after calling setBlockState
        // If we store the mutable pos in the map, the map key gets corrupted!
        BlockPos immutablePos = pos.toImmutable();
        
        // Check if there's already a block at this position
        BlockState existing = blockStates.get(immutablePos);
        
        // If there's an existing non-air block, check if it can be replaced by tree generation
        if (existing != null && !existing.isAir()) {
            // Tree generation uses TreeFeature.canTreeReplace() which allows:
            // - Air (obviously)
            // - Leaves (all types)
            // - Vines
            // - Moss
            // - Grass, tall grass, ferns
            // - Saplings
            // - Water, lava
            // But NOT logs, wood, dirt, stone, etc.
            
            Block existingBlock = existing.getBlock();
            String blockId = existingBlock.getRegistryEntry().getKey().get().getValue().toString();
            
            // Check if it's tree-replaceable
            boolean canReplace = existing.isReplaceable() || // Grass, flowers, etc.
                                blockId.contains("leaves") ||
                                blockId.contains("vine") ||
                                blockId.contains("moss") ||
                                blockId.contains("grass") ||
                                blockId.contains("fern") ||
                                blockId.contains("sapling");
            
            if (!canReplace) {
                // Block cannot be replaced by tree generation
                return false;
            }
        }
        
        // Valid placement - update the world state
        blockStates.put(immutablePos, state);
        placedBlocks.add(new BlockInfo(immutablePos.getX(), immutablePos.getY(), immutablePos.getZ(), state));
        return true;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        // Check if we have a placed block at this position
        BlockState state = blockStates.get(pos);
        if (state != null) {
            return state;
        }
        
        // Simulate a ground layer for tree placement validation
        // Many trees check for valid soil blocks (dirt, grass, podzol, etc.)
        // Trees spawn at y=0, so they check y=-1 for soil
        if (pos.getY() == -1) {
            return Blocks.GRASS_BLOCK.getDefaultState();
        } else if (pos.getY() < -1) {
            return Blocks.DIRT.getDefaultState();
        }
        
        // Return air above ground
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Fluids.EMPTY.getDefaultState();
    }

    @Override
    public boolean isAir(BlockPos pos) {
        BlockState state = blockStates.get(pos);
        return state == null || state.isAir();
    }

    @Override
    public DynamicRegistryManager getRegistryManager() {
        return registryManager;
    }

    @Override
    public Random getRandom() {
        return random;
    }

    // --- Stubs for required methods ---

    @Override
    public long getSeed() { return 0; }

    @Override
    public long getTickOrder() { return 0; }

    @Override
    public QueryableTickScheduler<Block> getBlockTickScheduler() {
        return new QueryableTickScheduler<Block>() {
            @Override
            public boolean isQueued(BlockPos pos, Block type) { return false; }
            @Override
            public void scheduleTick(net.minecraft.world.tick.OrderedTick<Block> orderedTick) {}
            @Override
            public boolean isTicking(BlockPos pos, Block type) { return false; }
            @Override
            public int getTickCount() { return 0; }
        };
    }

    @Override
    public QueryableTickScheduler<net.minecraft.fluid.Fluid> getFluidTickScheduler() {
        return new QueryableTickScheduler<net.minecraft.fluid.Fluid>() {
            @Override
            public boolean isQueued(BlockPos pos, net.minecraft.fluid.Fluid type) { return false; }
            @Override
            public void scheduleTick(net.minecraft.world.tick.OrderedTick<net.minecraft.fluid.Fluid> orderedTick) {}
            @Override
            public boolean isTicking(BlockPos pos, net.minecraft.fluid.Fluid type) { return false; }
            @Override
            public int getTickCount() { return 0; }
        };
    }

    @Override
    public MinecraftServer getServer() { return server; }

    @Override
    public net.minecraft.world.Difficulty getDifficulty() { return net.minecraft.world.Difficulty.NORMAL; }

    @Override
    public net.minecraft.world.LocalDifficulty getLocalDifficulty(BlockPos pos) { return null; }

    @Override
    public Chunk getChunk(int chunkX, int chunkZ, ChunkStatus leastStatus, boolean create) { return null; }

    public void playSound(@Nullable Entity player, BlockPos pos, SoundEvent sound, SoundCategory category, float volume, float pitch) {}

    public void addParticle(net.minecraft.particle.ParticleEffect parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ) {}

    @Override
    public void syncWorldEvent(@Nullable Entity player, int eventId, BlockPos pos, int data) {}

    public void emitGameEvent(GameEvent event, net.minecraft.util.math.Vec3d emitterPos, GameEvent.Emitter emitter) {}

    @Override
    public RegistryEntry<Biome> getBiome(BlockPos pos) {
        return registryManager.getOptional(RegistryKeys.BIOME)
            .flatMap(reg -> reg.getOptional(BiomeKeys.PLAINS))
            .orElseThrow(() -> new IllegalStateException("Biome registry not found"));
    }

    public List<Entity> getOtherEntities(@Nullable Entity except, net.minecraft.util.math.Box box, Predicate<? super Entity> predicate) { return java.util.Collections.emptyList(); }

    public <T extends Entity> List<T> getEntitiesByClass(Class<T> entityClass, net.minecraft.util.math.Box box, Predicate<? super T> predicate) { return java.util.Collections.emptyList(); }

    @Override
    public FeatureSet getEnabledFeatures() { return FeatureSet.empty(); }

    @Override
    public DimensionType getDimension() {
        return registryManager.getOptional(RegistryKeys.DIMENSION_TYPE)
            .flatMap(reg -> reg.getOptional(net.minecraft.world.dimension.DimensionTypes.OVERWORLD))
            .map(entry -> entry.value())
            .orElseThrow(() -> new IllegalStateException("Dimension registry not found"));
    }

    public RegistryEntry<DimensionType> getDimensionEntry() {
        return registryManager.getOptional(RegistryKeys.DIMENSION_TYPE)
            .flatMap(reg -> reg.getOptional(net.minecraft.world.dimension.DimensionTypes.OVERWORLD))
            .orElseThrow(() -> new IllegalStateException("Dimension registry not found"));
    }

    @Override
    public float getBrightness(Direction direction, boolean shaded) { return 1.0f; }

    @Override
    public int getLightLevel(net.minecraft.world.LightType type, BlockPos pos) { return 15; }

    @Override
    public boolean isChunkLoaded(int chunkX, int chunkZ) { return true; }

    @Override
    public WorldBorder getWorldBorder() { return new WorldBorder(); }

    @Override
    public boolean testBlockState(BlockPos pos, Predicate<BlockState> state) {
        return state.test(getBlockState(pos));
    }
    
    @Override
    public boolean breakBlock(BlockPos pos, boolean drop, @Nullable Entity breakingEntity, int maxUpdateDepth) { return true; }

    public void emitGameEvent(RegistryEntry<GameEvent> event, net.minecraft.util.math.Vec3d emitterPos, GameEvent.Emitter emitter) {}

    public boolean replaceBlock(BlockPos pos, BlockState oldState, BlockState newState, int flags) {
        return setBlockState(pos, newState, flags);
    }

    public void addParticleClient(net.minecraft.particle.ParticleEffect parameters, double x, double y, double z, double velocityX, double velocityY, double velocityZ) {}

    @Override
    public net.minecraft.server.world.ServerWorld toServerWorld() {
        return null;
    }

    @Override
    public net.minecraft.world.chunk.ChunkManager getChunkManager() {
        return null;
    }

    public ChunkGenerator getChunkGenerator() {
        return new ChunkGenerator(new FixedBiomeSource(registryManager.getOptional(RegistryKeys.BIOME).orElseThrow().getOrThrow(BiomeKeys.PLAINS))) {
            @Override
            protected MapCodec<? extends ChunkGenerator> getCodec() {
                return null;
            }

            @Override
            public void buildSurface(ChunkRegion region, StructureAccessor structures, NoiseConfig noiseConfig, Chunk chunk) {
            }

            @Override
            public void populateEntities(ChunkRegion region) {
            }

            @Override
            public void carve(ChunkRegion region, long seed, NoiseConfig noiseConfig, BiomeAccess biomeAccess, StructureAccessor structureAccessor, Chunk chunk) {
            }

            @Override
            public int getWorldHeight() {
                return 384;
            }

            @Override
            public CompletableFuture<Chunk> populateNoise(Blender blender, NoiseConfig noiseConfig, StructureAccessor structureAccessor, Chunk chunk) {
                 return CompletableFuture.completedFuture(chunk);
            }

            @Override
            public int getSeaLevel() {
                return 63;
            }

            @Override
            public int getMinimumY() {
                return -64;
            }

            @Override
            public int getHeight(int x, int z, Heightmap.Type heightmap, net.minecraft.world.HeightLimitView world, NoiseConfig noiseConfig) {
                return 64;
            }

            @Override
            public VerticalBlockSample getColumnSample(int x, int z, net.minecraft.world.HeightLimitView world, NoiseConfig noiseConfig) {
                return new VerticalBlockSample(getMinimumY(), new BlockState[0]);
            }

            @Override
            public void appendDebugHudText(List<String> text, NoiseConfig noiseConfig, BlockPos pos) {
            }
        };
    }

    @Override
    public net.minecraft.world.WorldProperties getLevelProperties() {
        return new net.minecraft.world.level.LevelProperties(new net.minecraft.world.level.LevelInfo("phantom", net.minecraft.world.GameMode.CREATIVE, false, net.minecraft.world.Difficulty.NORMAL, false, new net.minecraft.world.GameRules(net.minecraft.resource.featuretoggle.FeatureSet.empty()), net.minecraft.resource.DataConfiguration.SAFE_MODE), new net.minecraft.world.gen.GeneratorOptions(0, false, false), null, com.mojang.serialization.Lifecycle.stable());
    }

    @Override
    public List<? extends net.minecraft.entity.player.PlayerEntity> getPlayers() {
        return java.util.Collections.emptyList();
    }

    @Override
    public <T extends Entity> List<T> getEntitiesByType(net.minecraft.util.TypeFilter<Entity, T> filter, net.minecraft.util.math.Box box, Predicate<? super T> predicate) {
        return java.util.Collections.emptyList();
    }

    @Override
    public int getSeaLevel() {
        return 63;
    }

    @Override
    public boolean isClient() {
        return false;
    }

    @Override
    public RegistryEntry<Biome> getGeneratorStoredBiome(int biomeX, int biomeY, int biomeZ) {
        return getBiome(new BlockPos(biomeX, biomeY, biomeZ));
    }

    @Override
    public net.minecraft.world.biome.source.BiomeAccess getBiomeAccess() {
        // Create a BiomeAccess that uses our registry manager
        // We use reflection to access the constructor if needed, or just return a dummy one
        // But since we are in the same package structure (net.minecraft...), we might have access issues if not careful.
        // Actually, BiomeAccess is in net.minecraft.world.biome.source
        
        // Let's try to return a new instance using the public constructor if available,
        // or use a subclass that exposes what we need.
        // The constructor BiomeAccess(Storage, long) is what we used before.
        
        // Wait, the error is NullPointerException in net.minecraft.class_5217.method_188()
        // class_5217 is likely BiomeAccess or something related.
        // method_188 might be getBiome().
        
        // Let's try to return a fully functional BiomeAccess using a custom Storage
        return new net.minecraft.world.biome.source.BiomeAccess(
            new net.minecraft.world.biome.source.BiomeAccess.Storage() {
                @Override
                public RegistryEntry<Biome> getBiomeForNoiseGen(int x, int y, int z) {
                    return registryManager.getOptional(RegistryKeys.BIOME)
                        .flatMap(reg -> reg.getOptional(BiomeKeys.PLAINS))
                        .orElseThrow(() -> new IllegalStateException("Biome registry not found"));
                }
            },
            0L
        );
    }

    @Override
    public int getAmbientDarkness() {
        return 0;
    }

    public int getHeight() {
        return 384;
    }

    @Override
    public int getBottomY() {
        return -64;
    }

    public int getTopY() {
        return 320;
    }

    @Override
    public int getTopY(net.minecraft.world.Heightmap.Type heightmap, int x, int z) {
        return 320;
    }

    @Override
    public net.minecraft.world.chunk.light.LightingProvider getLightingProvider() {
        return null;
    }

    @Override
    public int getColor(BlockPos pos, net.minecraft.world.biome.ColorResolver colorResolver) {
        return 0;
    }

    @Override
    public net.minecraft.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public boolean testFluidState(BlockPos pos, Predicate<FluidState> state) {
        return state.test(getFluidState(pos));
    }

    @Override
    public boolean removeBlock(BlockPos pos, boolean move) {
        return setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
    }

    @Override
    public boolean setBlockState(BlockPos pos, BlockState state, int flags) {
        return setBlockState(pos, state, flags, 512);
    }
}
