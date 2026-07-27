package savage.tree_engine.preview.chunk;

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
	/** Highest block decoration has written per column, keyed by packed x/z. */
	private final Map<Long, Integer> placedTop = new HashMap<>();
	/** Columns whose top was cleared, so only a scan can answer for them. */
	private final java.util.Set<Long> rescan = new java.util.HashSet<>();
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
	 * was placed on it, every non-air block between the cut heights.
	 *
	 * This used to drop any block sealed in on all six sides, on the reasoning
	 * that a block with six opaque neighbours contributes no visible face. That
	 * was true of the first frame and false of everything else: those blocks are
	 * simply absent, so a mountain arrives as a hollow shell, and any view that
	 * gets inside it - or any later decision about what to draw - has nothing to
	 * work with. It also disagreed with the renderer about what "opaque" means
	 * ({@code canOcclude} here versus {@code isSolidRender} in BlockFlagsDto), so
	 * the two culls did not compose: a block could be dropped here that the
	 * renderer would have drawn faces for.
	 *
	 * Hiding blocks is the renderer's job, and it does it per face at draw time,
	 * the way the game does. This reports what generated.
	 */
	public List<BlockDto> fullChunk(TerrainSnapshot snapshot, int floorY, int ceilingY) {
		List<BlockDto> out = new ArrayList<>();
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		int baseX = snapshot.pos().getMinBlockX();
		int baseZ = snapshot.pos().getMinBlockZ();
		int lo = Math.max(floorY, snapshot.minY());
		int hi = Math.min(ceilingY, snapshot.minY() + snapshot.height() - 1);

		for (int y = lo; y <= hi; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					cursor.set(baseX + x, y, baseZ + z);
					BlockState state = getBlockState(cursor);
					if (state.isAir()) {
						continue;
					}
					out.add(BlockDto.of(cursor.getX(), cursor.getY(), cursor.getZ(), state));
				}
			}
		}
		return out;
	}

	/**
	 * Blocks decoration placed outside the given chunks.
	 *
	 * A tree near a chunk edge legitimately writes part of its canopy into the
	 * neighbour. Those blocks belong to the preview - dropping them slices the
	 * tree cleanly in half along the chunk border.
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

		// Keep the column tops that getHeight reads in step with the write.
		long column = columnKey(pos.getX(), pos.getZ());
		if (state.isAir()) {
			// Clearing a block can only ever lower a top, and the new top is
			// whatever lies beneath - which needs a scan to find. Only bother
			// when the cleared block could actually have been the top.
			if (pos.getY() >= cachedTop(column, pos.getX(), pos.getZ())) {
				placedTop.remove(column);
				rescan.add(column);
			}
		} else {
			placedTop.merge(column, pos.getY(), Math::max);
		}
		return true;
	}

	/** The column's top as the fast path currently believes it to be. */
	private int cachedTop(long column, int x, int z) {
		TerrainSnapshot snapshot = terrain.get(ChunkPos.pack(x >> 4, z >> 4));
		int top = snapshot != null ? snapshot.topNonAir(x, z) : TerrainSnapshot.NO_BLOCKS;
		Integer written = placedTop.get(column);
		return written != null && written > top ? written : top;
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
	 * Derived from the terrain rather than read from a chunk heightmap, because
	 * writes shadow the snapshot and a stale heightmap would put features inside
	 * the terrain they were meant to sit on.
	 *
	 * <p>This used to scan the whole column top-down, which measured at ~29us a
	 * call and made decoration 93% of a preview's runtime: the world is 384
	 * blocks tall, the terrain tops out around 100, and features ask for a
	 * height on essentially every placement attempt, so almost every call burnt
	 * ~280 iterations of pure air before reaching anything. It is now the
	 * snapshot's precomputed terrain top combined with the highest block
	 * decoration has since written into the column, which is the same answer in
	 * O(1).
	 *
	 * <p>The {@code type} is ignored, exactly as it always has been - every
	 * heightmap type here means "first non-air from the top". Preserved
	 * deliberately: changing it would change where features land.
	 */
	@Override
	public int getHeight(Heightmap.Types type, int x, int z) {
		TerrainSnapshot snapshot = terrain.get(ChunkPos.pack(x >> 4, z >> 4));
		if (snapshot == null) {
			return getMinY();
		}
		long column = columnKey(x, z);
		if (rescan.contains(column)) {
			return scanColumn(snapshot, x, z);
		}
		int top = snapshot.topNonAir(x, z);
		Integer written = placedTop.get(column);
		if (written != null && written > top) {
			top = written;
		}
		return top == TerrainSnapshot.NO_BLOCKS ? snapshot.minY() : top + 1;
	}

	/**
	 * The old full-column walk, kept for columns the fast path cannot answer:
	 * once decoration has *removed* the block that was a column's top, the
	 * cached top is an upper bound rather than the answer, and only a scan
	 * knows what is underneath it. Features that clear blocks are rare enough
	 * that this stays off the hot path.
	 */
	private int scanColumn(TerrainSnapshot snapshot, int x, int z) {
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = snapshot.minY() + snapshot.height() - 1; y >= snapshot.minY(); y--) {
			cursor.set(x, y, z);
			if (!getBlockState(cursor).isAir()) {
				return y + 1;
			}
		}
		return snapshot.minY();
	}

	private static long columnKey(int x, int z) {
		return ((long) x << 32) | (z & 0xffffffffL);
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

	/**
	 * Cached because this used to allocate a fresh manager on every call, and
	 * the biome grid asks per column.
	 */
	private BiomeManager biomeManager;

	@Override
	public BiomeManager getBiomeManager() {
		if (biomeManager == null) {
			biomeManager = new BiomeManager(this, seed);
		}
		return biomeManager;
	}

	/**
	 * The biome a column should be coloured by: the one at its surface.
	 *
	 * <p>Goes through {@link BiomeManager} rather than reading the noise biome
	 * directly, because that is what the game does when it colours a block -
	 * the manager applies a fuzzy offset that softens the 4x4 cell edges, and
	 * skipping it would give the preview visibly blockier biome borders than
	 * the real world has.
	 */
	public Holder<Biome> surfaceBiome(int x, int z) {
		int surface = getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
		return getBiomeManager().getBiome(new BlockPos(x, surface, z));
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
