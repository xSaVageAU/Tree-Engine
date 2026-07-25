package savage.tree_engine.preview.tree;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.ticks.LevelTickAccess;
import net.minecraft.world.ticks.ScheduledTick;
import net.minecraft.world.ticks.TickPriority;
import savage.tree_engine.preview.BlockDto;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A {@link WorldGenLevel} that exists only to record what a feature tries to
 * place. Nothing is written to a real world, no chunks are loaded, and no
 * ticks are scheduled.
 *
 * Reads below the origin are answered by {@link GroundPlane}; reads above it
 * are air until the feature fills them in. Every write is captured in a map
 * keyed by position, so the final state of a block is what gets reported even
 * if the feature overwrites it mid-generation.
 *
 * Single-tree previews only. Natural chunk generation uses real terrain and
 * must not go through this class.
 */
public final class CaptureLevel implements WorldGenLevel {
	private final MinecraftServer server;
	private final RegistryAccess registries;
	private final GroundPlane ground;
	private final RandomSource random;
	private final Map<BlockPos, BlockState> placed = new LinkedHashMap<>();

	public CaptureLevel(
		MinecraftServer server, RegistryAccess registries, GroundPlane ground, RandomSource random) {
		this.server = server;
		this.registries = registries;
		this.ground = ground;
		this.random = random;
	}

	/**
	 * The captured tree, excluding the fabricated ground. Positions the
	 * feature merely read are not included - only what it actually placed.
	 */
	public List<BlockDto> captured(boolean includeGround) {
		List<BlockDto> out = new ArrayList<>(placed.size());
		for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
			BlockPos pos = entry.getKey();
			if (!includeGround && ground.isGround(pos)) {
				continue;
			}
			out.add(BlockDto.of(pos.getX(), pos.getY(), pos.getZ(), entry.getValue()));
		}
		return out;
	}

	// --- writes -------------------------------------------------------

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
		// Tree placers reuse a MutableBlockPos and keep mutating it after
		// this call, so the key must be an immutable snapshot or every entry
		// in the map ends up pointing at the same final position.
		placed.put(pos.immutable(), state);
		return true;
	}

	@Override
	public boolean removeBlock(BlockPos pos, boolean isMoving) {
		return setBlock(pos, Blocks.AIR.defaultBlockState(), 3, 512);
	}

	@Override
	public boolean destroyBlock(BlockPos pos, boolean dropBlock, Entity entity, int recursionLeft) {
		return removeBlock(pos, false);
	}

	// --- reads --------------------------------------------------------

	@Override
	public BlockState getBlockState(BlockPos pos) {
		BlockState state = placed.get(pos);
		return state != null ? state : ground.blockAt(pos);
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return Fluids.EMPTY.defaultFluidState();
	}

	@Override
	public boolean isStateAtPosition(BlockPos pos, Predicate<BlockState> predicate) {
		return predicate.test(getBlockState(pos));
	}

	@Override
	public boolean isFluidAtPosition(BlockPos pos, Predicate<FluidState> predicate) {
		return predicate.test(getFluidState(pos));
	}

	@Override
	public BlockPos getHeightmapPos(Heightmap.Types type, BlockPos pos) {
		// The fabricated world is flat: the surface is always just above the
		// soil plane, regardless of x/z.
		return new BlockPos(pos.getX(), GroundPlane.SURFACE_Y + 1, pos.getZ());
	}

	@Override
	public int getHeight(Heightmap.Types type, int x, int z) {
		return GroundPlane.SURFACE_Y + 1;
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
		return Optional.empty();
	}

	// --- biome --------------------------------------------------------

	@Override
	public Holder<Biome> getNoiseBiome(int x, int y, int z) {
		return ground.biome();
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
		return ground.biome();
	}

	@Override
	public BiomeManager getBiomeManager() {
		return new BiomeManager(this, 0L);
	}

	// --- world shape --------------------------------------------------

	@Override
	public int getHeight() {
		return 384;
	}

	@Override
	public int getMinY() {
		return -64;
	}

	@Override
	public int getSeaLevel() {
		return 63;
	}

	@Override
	public long getSeed() {
		return 0L;
	}

	@Override
	public DimensionType dimensionType() {
		return server.overworld().dimensionType();
	}

	@Override
	public RegistryAccess registryAccess() {
		return registries;
	}

	@Override
	public FeatureFlagSet enabledFeatures() {
		return server.getWorldData().enabledFeatures();
	}

	@Override
	public net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes() {
		return server.overworld().environmentAttributes();
	}

	@Override
	public RandomSource getRandom() {
		return random;
	}

	@Override
	public MinecraftServer getServer() {
		return server;
	}

	@Override
	public ServerLevel getLevel() {
		// Required by ServerLevelAccessor. Features that reach for the real
		// level are out of scope for a preview, but returning the overworld
		// is safer than null for the ones that only query it.
		return server.overworld();
	}

	@Override
	public LevelData getLevelData() {
		return server.overworld().getLevelData();
	}

	@Override
	public DifficultyInstance getCurrentDifficultyAt(BlockPos pos) {
		return server.overworld().getCurrentDifficultyAt(pos);
	}

	@Override
	public int getSkyDarken() {
		return 0;
	}

	@Override
	public boolean isClientSide() {
		return false;
	}

	// --- chunks, lighting, collision: absent by design ------------------

	@Override
	public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean requireChunk) {
		return null;
	}

	@Override
	public boolean hasChunk(int x, int z) {
		return true;
	}

	@Override
	public ChunkSource getChunkSource() {
		return null;
	}

	@Override
	public LevelLightEngine getLightEngine() {
		return null;
	}

	@Override
	public WorldBorder getWorldBorder() {
		return new WorldBorder();
	}

	@Override
	public BlockGetter getChunkForCollisions(int x, int z) {
		return this;
	}

	@Override
	public List<VoxelShape> getEntityCollisions(Entity entity, AABB box) {
		return Collections.emptyList();
	}

	// --- entities: none ever exist here --------------------------------

	@Override
	public List<Entity> getEntities(Entity except, AABB box, Predicate<? super Entity> predicate) {
		return Collections.emptyList();
	}

	@Override
	public <T extends Entity> List<T> getEntities(
		EntityTypeTest<Entity, T> test, AABB box, Predicate<? super T> predicate) {
		return Collections.emptyList();
	}

	@Override
	public List<? extends Player> players() {
		return Collections.emptyList();
	}

	// --- ticking and effects: intentionally inert -----------------------

	@Override
	public long nextSubTickCount() {
		return 0L;
	}

	@Override
	public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay, TickPriority priority) {
		return new ScheduledTick<>(type, pos, 0L, priority, 0L);
	}

	@Override
	public <T> ScheduledTick<T> createTick(BlockPos pos, T type, int delay) {
		return new ScheduledTick<>(type, pos, 0L, 0L);
	}

	@Override
	public LevelTickAccess<net.minecraft.world.level.block.Block> getBlockTicks() {
		return InertTicks.instance();
	}

	@Override
	public LevelTickAccess<Fluid> getFluidTicks() {
		return InertTicks.instance();
	}

	@Override
	public void playSound(Entity entity, BlockPos pos, SoundEvent sound, SoundSource source,
						  float volume, float pitch) {
	}

	@Override
	public void addParticle(ParticleOptions particle, double x, double y, double z,
							double xSpeed, double ySpeed, double zSpeed) {
	}

	@Override
	public void levelEvent(Entity entity, int type, BlockPos pos, int data) {
	}

	@Override
	public void gameEvent(Holder<GameEvent> event, Vec3 pos, GameEvent.Context context) {
	}

	@Override
	public void setCurrentlyGenerating(Supplier<String> caller) {
	}

	/** A tick scheduler that accepts everything and remembers nothing. */
	private static final class InertTicks<T> implements LevelTickAccess<T> {
		private static final InertTicks<?> INSTANCE = new InertTicks<>();

		@SuppressWarnings("unchecked")
		static <T> LevelTickAccess<T> instance() {
			return (LevelTickAccess<T>) INSTANCE;
		}

		@Override
		public boolean hasScheduledTick(BlockPos pos, T type) {
			return false;
		}

		@Override
		public void schedule(ScheduledTick<T> tick) {
		}

		@Override
		public boolean willTickThisTick(BlockPos pos, T type) {
			return false;
		}

		@Override
		public int count() {
			return 0;
		}
	}
}
