package savage.tree_engine.preview.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * A {@link WorldGenLevel} spanning a small grid of snapshotted chunks, used to
 * run real feature decoration over real terrain.
 *
 * Reads fall through to the terrain snapshot; writes are recorded and shadow
 * it. Unlike the single-tree mode there is no fabricated ground and no fixed
 * biome - the ground shape, surface materials and biomes are whatever the
 * world actually generated, which is the entire point of this preview mode.
 *
 * This class intentionally shares no code with {@code preview.tree}: the two
 * modes answer different questions, and letting the flat-plane assumptions
 * leak in here would produce output that looks right and is wrong.
 */
public final class ChunkPreviewLevel implements WorldGenLevel {
	private final MinecraftServer server;
	private final RegistryAccess registries;
	private final Map<Long, TerrainSnapshot> terrain = new HashMap<>();
	private final Map<BlockPos, BlockState> placed = new LinkedHashMap<>();
	private final RandomSource random;
	private final long seed;

	public ChunkPreviewLevel(
		MinecraftServer server, RegistryAccess registries,
		List<TerrainSnapshot> snapshots, RandomSource random, long seed) {
		this.server = server;
		this.registries = registries;
		this.random = random;
		this.seed = seed;
		for (TerrainSnapshot snapshot : snapshots) {
			terrain.put(ChunkPos.pack(snapshot.pos().x(), snapshot.pos().z()), snapshot);
		}
	}

	/** Everything decoration placed, i.e. the difference from bare terrain. */
	public List<BlockDto> decorated() {
		List<BlockDto> out = new ArrayList<>(placed.size());
		for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
			BlockPos pos = entry.getKey();
			out.add(BlockDto.of(pos.getX(), pos.getY(), pos.getZ(), entry.getValue()));
		}
		return out;
	}

	/**
	 * The full contents of one chunk after decoration - terrain plus whatever
	 * was placed on it. This is what a renderer showing "the chunk as it would
	 * appear in game" needs.
	 */
	public List<BlockDto> fullChunk(TerrainSnapshot snapshot, Window window) {
		List<BlockDto> out = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int baseX = snapshot.pos().getMinBlockX();
		int baseZ = snapshot.pos().getMinBlockZ();
		int lo = Math.max(window.minY(), snapshot.minY());
		int hi = Math.min(window.maxY(), snapshot.minY() + snapshot.height() - 1);

		for (int y = lo; y <= hi; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					cursor.set(baseX + x, y, baseZ + z);
					BlockState state = getBlockState(cursor);
					if (state.isAir() || hidden(cursor, window)) {
						continue;
					}
					out.add(BlockDto.of(cursor.getX(), cursor.getY(), cursor.getZ(), state));
				}
			}
		}
		return out;
	}

	/**
	 * Whether a block is sealed in on all six sides and therefore cannot be
	 * seen from outside the preview.
	 *
	 * Cutting the world at a flat height means everything below the surface
	 * comes back solid, and the overwhelming majority of it is buried stone.
	 * Dropping it is not a visual compromise - a block with six opaque
	 * neighbours contributes no visible face - but it removes most of the
	 * volume, which is what makes a flat cutoff affordable at all.
	 *
	 * A face on the edge of the window counts as exposed, so the cut plane and
	 * the outer walls stay solid and the result reads as a cross-section
	 * rather than a hollow shell.
	 */
	private boolean hidden(BlockPos pos, Window window) {
		BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
		for (Direction direction : Direction.values()) {
			neighbour.set(pos).move(direction);
			if (!window.contains(neighbour)) {
				return false;
			}
			if (!getBlockState(neighbour).canOcclude()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The box a preview covers. Anything outside it is treated as open space,
	 * which is what keeps the boundary faces visible.
	 */
	public record Window(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
		public boolean contains(BlockPos pos) {
			return pos.getX() >= minX && pos.getX() <= maxX
				&& pos.getY() >= minY && pos.getY() <= maxY
				&& pos.getZ() >= minZ && pos.getZ() <= maxZ;
		}
	}

	/**
	 * Blocks decoration placed outside the given chunks.
	 *
	 * A tree near a chunk edge legitimately writes part of its canopy into the
	 * neighbour. Those blocks belong to the preview - dropping them slices the
	 * tree cleanly in half along the chunk border.
	 *
	 * These are not interior-culled: they sit outside the emitted box by
	 * definition, so nothing around them is solid.
	 */
	public List<BlockDto> spillOutside(java.util.Set<Long> chunks, int fromY, int toY) {
		List<BlockDto> out = new ArrayList<>();
		for (Map.Entry<BlockPos, BlockState> entry : placed.entrySet()) {
			BlockPos pos = entry.getKey();
			if (entry.getValue().isAir()) {
				continue;
			}
			// Spill obeys the same window as everything else. Underground
			// decoration - sculk in a deep dark, say - also crosses chunk
			// borders, and without this it comes back from far below the cut.
			if (pos.getY() < fromY || pos.getY() > toY) {
				continue;
			}
			if (chunks.contains(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4))) {
				continue;
			}
			out.add(BlockDto.of(pos.getX(), pos.getY(), pos.getZ(), entry.getValue()));
		}
		return out;
	}

	/**
	 * The highest occupied block across a chunk, counting decoration.
	 * Used to fit the preview window to what is actually there.
	 */
	public int highestOccupied(TerrainSnapshot snapshot) {
		int baseX = snapshot.pos().getMinBlockX();
		int baseZ = snapshot.pos().getMinBlockZ();
		int highest = snapshot.minY();
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				highest = Math.max(highest, getHeight(Heightmap.Types.MOTION_BLOCKING, baseX + x, baseZ + z));
			}
		}
		return highest;
	}

	/** The lowest surface height across a chunk - where the ground starts. */
	public int lowestSurface(TerrainSnapshot snapshot) {
		int baseX = snapshot.pos().getMinBlockX();
		int baseZ = snapshot.pos().getMinBlockZ();
		int lowest = Integer.MAX_VALUE;
		for (int z = 0; z < 16; z++) {
			for (int x = 0; x < 16; x++) {
				lowest = Math.min(lowest, getHeight(Heightmap.Types.MOTION_BLOCKING, baseX + x, baseZ + z));
			}
		}
		return lowest == Integer.MAX_VALUE ? snapshot.minY() : lowest;
	}

	private TerrainSnapshot snapshotFor(BlockPos pos) {
		return terrain.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
	}

	// --- writes -------------------------------------------------------

	@Override
	public boolean setBlock(BlockPos pos, BlockState state, int flags, int recursionLeft) {
		// Decoration legitimately spills a block or two outside the requested
		// chunks (a tree on the border). Those writes are accepted and
		// reported; the renderer can clip them if it wants.
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
		BlockState written = placed.get(pos);
		if (written != null) {
			return written;
		}
		TerrainSnapshot snapshot = snapshotFor(pos);
		return snapshot != null ? snapshot.blockAt(pos) : Blocks.AIR.defaultBlockState();
	}

	@Override
	public FluidState getFluidState(BlockPos pos) {
		return getBlockState(pos).getFluidState();
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
		return new BlockPos(pos.getX(), getHeight(type, pos.getX(), pos.getZ()), pos.getZ());
	}

	/**
	 * Derived by scanning the column rather than read from a heightmap,
	 * because writes shadow the snapshot and a stale heightmap would put
	 * features inside the terrain they were meant to sit on.
	 */
	@Override
	public int getHeight(Heightmap.Types type, int x, int z) {
		TerrainSnapshot snapshot = terrain.get(ChunkPos.pack(x >> 4, z >> 4));
		if (snapshot == null) {
			return getMinY();
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = snapshot.minY() + snapshot.height() - 1; y >= snapshot.minY(); y--) {
			cursor.set(x, y, z);
			if (!getBlockState(cursor).isAir()) {
				return y + 1;
			}
		}
		return snapshot.minY();
	}

	@Override
	public BlockEntity getBlockEntity(BlockPos pos) {
		return null;
	}

	@Override
	public <T extends BlockEntity> Optional<T> getBlockEntity(BlockPos pos, BlockEntityType<T> type) {
		return Optional.empty();
	}

	// --- biomes: the world's real ones ---------------------------------

	/**
	 * The world's real biome at this position.
	 *
	 * Deliberately <em>not</em> swapped for the session's copy of the same
	 * biome. Returning a session biome here while decoration iterated the
	 * world's feature lists made every feature fail validation and previews
	 * came out empty - 500 decorated blocks became 0. The datapack takes
	 * effect through {@link SessionGenerator} instead, which redirects the
	 * feature lists and the validation together so both sides stay
	 * consistent.
	 */
	@Override
	public Holder<Biome> getNoiseBiome(int x, int y, int z) {
		TerrainSnapshot snapshot = terrain.get(ChunkPos.pack((x << 2) >> 4, (z << 2) >> 4));
		if (snapshot == null) {
			snapshot = terrain.values().iterator().next();
		}
		return snapshot.biomeAt(x, y, z);
	}

	@Override
	public Holder<Biome> getUncachedNoiseBiome(int x, int y, int z) {
		return getNoiseBiome(x, y, z);
	}

	@Override
	public BiomeManager getBiomeManager() {
		return new BiomeManager(this, seed);
	}

	// --- world shape ---------------------------------------------------

	@Override
	public int getHeight() {
		return terrain.values().iterator().next().height();
	}

	@Override
	public int getMinY() {
		return terrain.values().iterator().next().minY();
	}

	@Override
	public int getSeaLevel() {
		return server.overworld().getSeaLevel();
	}

	@Override
	public long getSeed() {
		return seed;
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

	// --- chunks ---------------------------------------------------------

	@Override
	public ChunkAccess getChunk(int x, int z, ChunkStatus status, boolean requireChunk) {
		TerrainSnapshot snapshot = terrain.get(ChunkPos.pack(x, z));
		return snapshot != null ? snapshot.sourceChunk() : null;
	}

	@Override
	public boolean hasChunk(int x, int z) {
		return terrain.containsKey(ChunkPos.pack(x, z));
	}

	@Override
	public ChunkSource getChunkSource() {
		return null;
	}

	/**
	 * There is no light engine, and the two default methods that would ask for
	 * one are overridden below so nothing ever dereferences this.
	 *
	 * Returning null here used to crash any preview in a cold biome:
	 * SnowAndFreezeFeature asks whether water should freeze, which reads block
	 * light, which went straight through getLightEngine().
	 */
	@Override
	public LevelLightEngine getLightEngine() {
		return null;
	}

	/**
	 * Light levels as they stand while features are being placed: zero.
	 *
	 * This is not a stand-in for real lighting, it is what real generation
	 * reports at this point. Chunks are lit after decoration - the status
	 * order is SURFACE, CARVERS, FEATURES, INITIALIZE_LIGHT, LIGHT - so a
	 * feature asking about light during placement sees an engine that has not
	 * propagated anything yet. Matching that keeps placement decisions the
	 * same as the game's, which is the point: it is why water in a snowy biome
	 * freezes during generation, and it will here too.
	 */
	@Override
	public int getBrightness(LightLayer layer, BlockPos pos) {
		return 0;
	}

	@Override
	public int getRawBrightness(BlockPos pos, int amount) {
		return 0;
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

	// --- entities: previews contain none --------------------------------

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

	// --- ticking and effects: inert -------------------------------------

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
