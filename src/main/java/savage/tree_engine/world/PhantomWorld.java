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
import net.minecraft.world.biome.BiomeKeys;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.event.GameEvent;
import net.minecraft.world.tick.QueryableTickScheduler;
import org.jetbrains.annotations.Nullable;
import savage.tree_engine.web.BlockInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

public class PhantomWorld implements StructureWorldAccess {
    private final List<BlockInfo> placedBlocks = new ArrayList<>();
    private final DynamicRegistryManager registryManager;
    private final Random random;

    public PhantomWorld(DynamicRegistryManager registryManager) {
        this.registryManager = registryManager;
        this.random = Random.create();
    }

    public List<BlockInfo> getPlacedBlocks() {
        return placedBlocks;
    }

    @Override
    public boolean setBlockState(BlockPos pos, BlockState state, int flags, int maxUpdateDepth) {
        placedBlocks.add(new BlockInfo(pos.getX(), pos.getY(), pos.getZ(), state.getBlock().getRegistryEntry().getKey().get().getValue().toString()));
        return true;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        // Return air by default so trees can grow
        return Blocks.AIR.getDefaultState();
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return Fluids.EMPTY.getDefaultState();
    }

    @Override
    public boolean isAir(BlockPos pos) {
        return true;
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
    public QueryableTickScheduler<Block> getBlockTickScheduler() { return null; }

    @Override
    public QueryableTickScheduler<net.minecraft.fluid.Fluid> getFluidTickScheduler() { return null; }

    @Override
    public MinecraftServer getServer() { return null; }

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
    public DimensionType getDimension() { return null; }

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

    @Override
    public net.minecraft.world.WorldProperties getLevelProperties() {
        return null;
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
        return null;
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
