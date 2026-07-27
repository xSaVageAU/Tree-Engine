package savage.tree_engine.preview.chunk;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.LevelStem;
import savage.tree_engine.api.ApiException;
import savage.tree_engine.api.ApiServer;
import savage.tree_engine.preview.BlockDto;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Generates what a chunk would actually look like in game with a datapack
 * applied.
 *
 * The split of responsibilities is deliberate. Terrain comes from the running
 * world, so the ground shape, surface materials and biomes are genuine and
 * this class never reimplements the noise pipeline. Decoration comes from the
 * <em>session's</em> registries, so the features placed are the ones the
 * user's datapack defines. That combination is what makes the preview
 * representative rather than illustrative.
 */
public final class ChunkPreviewer {
	/**
	 * Chunks are expensive: each is a full block array plus decoration.
	 * A preview is a viewport, not a world download.
	 *
	 * <p>What sets this ceiling is memory rather than time. Generation and
	 * decoration cost roughly 15ms a chunk, so even this many is a couple of
	 * seconds; but the response is built as a Gson tree, then a String, then a
	 * byte array, all live at once, and that is several hundred megabytes of
	 * transient heap inside the game's own JVM at 100 chunks. Raising this
	 * further wants a compact wire format first, not a faster generator.
	 */
	private static final int MAX_CHUNKS = 100;

	/**
	 * Decoration reads the chunks around the one it is populating - a tree
	 * near an edge checks its neighbours for room. Those neighbours must be
	 * snapshotted too or the lookup returns null mid-generation, so a margin
	 * is captured around the requested region and then discarded.
	 */
	private static final int MARGIN = 1;

	private final MinecraftServer server;

	public ChunkPreviewer(MinecraftServer server) {
		this.server = server;
	}

	/**
	 * Default floor for a preview.
	 *
	 * Below sea level (63) so shorelines and water are intact, and well above
	 * the deepslate transition (~0) so the visible profile is the terrain you
	 * build on. Everything below is simply not generated into the response,
	 * which is also the cheapest lever there is on preview size: every level
	 * raised is a full 16x16 slab per chunk that never has to be emitted,
	 * transferred or meshed.
	 */
	private static final int DEFAULT_FLOOR_Y = 50;

	/** Headroom above the tallest thing, so nothing is clipped at the top. */
	private static final int HEADROOM = 2;


	public Result preview(
		RegistryAccess registries, int centerX, int centerZ, int size,
		long seed, boolean fullChunk, Integer requestedMinY, Integer requestedMaxY) {

		if (size < 1) {
			throw ApiException.badRequest("size must be at least 1 chunk");
		}
		if (size * size > MAX_CHUNKS) {
			throw ApiException.badRequest(
				"Requested " + (size * size) + " chunks; the limit is " + MAX_CHUNKS);
		}

		// A square of `size` chunks, centred on the requested one as closely as
		// an even size allows: 1 -> just it, 2 -> it plus the +x/+z corner,
		// 3 -> it and all eight neighbours.
		int before = (size - 1) / 2;
		int after = size / 2;
		int minChunkX = centerX - before;
		int maxChunkX = centerX + after;
		int minChunkZ = centerZ - before;
		int maxChunkZ = centerZ + after;

		// Ask for every chunk at once, then wait for the lot.
		//
		// This must run off the server thread, and that is the whole point.
		// ServerChunkCache.getChunkFuture, called *from* the server thread,
		// managedBlocks until that one chunk is done - so generating a grid
		// from there is strictly serial, one cold chunk after another, with the
		// game loop stalled throughout. Called from anywhere else it merely
		// queues a cheap dispatch on the server thread and returns, so issuing
		// the whole grid up front lets Minecraft's worldgen workers run them
		// concurrently. At 6x6 that is 64 chunks (the grid plus its margin)
		// that used to be a serial ~100ms each.
		//
		// Correctness does not depend on this: called on the server thread it
		// still works, just serially, exactly as before.
		long tGenerate = System.nanoTime();
		ServerChunkCache chunkSource = server.overworld().getChunkSource();
		List<CompletableFuture<ChunkResult<ChunkAccess>>> pending = new ArrayList<>();
		for (int x = minChunkX - MARGIN; x <= maxChunkX + MARGIN; x++) {
			for (int z = minChunkZ - MARGIN; z <= maxChunkZ + MARGIN; z++) {
				pending.add(chunkSource.getChunkFuture(x, z, ChunkStatus.SURFACE, true));
			}
		}

		// Then drive the server thread to drain them, rather than just waiting.
		//
		// Every chunk status transition has to hop through the server thread,
		// and MinecraftServer.pollTask only drains that queue `while
		// (haveTime())` - the slack left in the current tick. Simply joining the
		// futures therefore advances generation in 50ms dribbles and is *slower*
		// than the old serial code, which went through managedBlock and spun the
		// drain flat out. Issuing the futures first and then spinning gets both:
		// the whole grid in flight at once, and the queue emptied as fast as the
		// CPU allows.
		CompletableFuture<Void> allDone =
			CompletableFuture.allOf(pending.toArray(new CompletableFuture[0]));
		server.submit(() -> server.managedBlock(allDone::isDone)).join();

		List<ChunkAccess> generated = new ArrayList<>(pending.size());
		for (CompletableFuture<ChunkResult<ChunkAccess>> future : pending) {
			ChunkResult<ChunkAccess> result = future.join();
			if (!result.isSuccess()) {
				throw ApiException.internal(
					"Chunk generation failed",
					new IllegalStateException(result.getError()));
			}
			generated.add(result.orElse(null));
		}

		long generateMs = millisSince(tGenerate);

		// Copying reads live chunks, so it has to happen on the server thread.
		// It is only an array copy now that generation is already done, so the
		// game loop is held for a fraction of what it was. Decoration afterwards
		// works purely on the copies and stays off the server thread entirely.
		long tCopy = System.nanoTime();
		List<TerrainSnapshot> all = server.submit(() -> {
			List<TerrainSnapshot> out = new ArrayList<>(generated.size());
			for (ChunkAccess chunk : generated) {
				out.add(TerrainSnapshot.capture(chunk));
			}
			return out;
		}).join();
		long copyMs = millisSince(tCopy);

		// Only the chunks the caller asked for get decorated and returned;
		// the margin exists purely so neighbour lookups resolve.
		List<TerrainSnapshot> requested = new ArrayList<>();
		for (TerrainSnapshot snapshot : all) {
			int x = snapshot.pos().x();
			int z = snapshot.pos().z();
			if (x >= minChunkX && x <= maxChunkX && z >= minChunkZ && z <= maxChunkZ) {
				requested.add(snapshot);
			}
		}

		long tDecorate = System.nanoTime();
		ChunkGenerator generator = generatorFor(registries);
		RandomSource random = RandomSource.create(seed);
		ChunkPreviewLevel level =
			new ChunkPreviewLevel(server, registries, all, random, seed);

		for (TerrainSnapshot snapshot : requested) {
			try {
				generator.applyBiomeDecoration(
					level, snapshot.sourceChunk(), server.overworld().structureManager());
			} catch (Exception e) {
				throw ApiException.internal(
					"Decoration failed for chunk " + snapshot.pos().x() + "," + snapshot.pos().z(), e);
			}
		}

		long decorateMs = millisSince(tDecorate);

		long tEmit = System.nanoTime();

		// The preview is cut at a flat height rather than fitted to each
		// column. Earlier versions were cleverer about this - fitting a window
		// to the surface, then a crust that followed it - and both produced
		// artefacts on sloped ground that looked like missing terrain. A
		// single honest cut reads as a cross-section. The floor is the only
		// thing that decides what is in a preview; everything above it that
		// generated is reported, and deciding what is visible is the renderer's
		// job.
		int floorY = requestedMinY != null ? requestedMinY : DEFAULT_FLOOR_Y;
		int ceilingY;
		if (requestedMaxY != null) {
			ceilingY = requestedMaxY;
		} else {
			int highest = Integer.MIN_VALUE;
			for (TerrainSnapshot snapshot : requested) {
				highest = Math.max(highest, level.highestOccupied(snapshot));
			}
			ceilingY = highest + HEADROOM;
		}
		if (ceilingY < floorY) {
			ceilingY = floorY;
		}

		List<BlockDto> blocks;
		if (fullChunk) {
			blocks = new ArrayList<>();
			for (TerrainSnapshot snapshot : requested) {
				blocks.addAll(level.fullChunk(snapshot, floorY, ceilingY));
			}
			// Decoration that reached past the requested chunks comes along,
			// so a tree on a border is not sliced in half.
			java.util.Set<Long> requestedKeys = new java.util.HashSet<>();
			for (TerrainSnapshot snapshot : requested) {
				requestedKeys.add(ChunkPos.pack(snapshot.pos().x(), snapshot.pos().z()));
			}
			blocks.addAll(level.spillOutside(requestedKeys, floorY, ceilingY));
		} else {
			// Obey the same cut as everything else. Ore generation is a placed
			// feature, so decoration runs from bedrock up: unfiltered, this comes
			// back as a mostly-empty column ~160 blocks tall whose bounding box is
			// all underground ore, which is not what "show me what my datapack
			// placed" means. decoratedCount below still reports the true total.
			blocks = new ArrayList<>();
			for (BlockDto block : level.decorated()) {
				if (block.y() >= floorY && block.y() <= ceilingY) {
					blocks.add(block);
				}
			}
		}

		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (BlockDto block : blocks) {
			minY = Math.min(minY, block.y());
			maxY = Math.max(maxY, block.y());
		}
		if (blocks.isEmpty()) {
			minY = floorY;
			maxY = floorY;
		}

		Timings timings = new Timings(
			generateMs, copyMs, decorateMs, millisSince(tEmit), generated.size());
		ApiServer.LOGGER.info(
			"Chunk preview {}x{} at {},{}: {} blocks, {}", size, size, centerX, centerZ,
			blocks.size(), timings);

		return new Result(
			blocks, requested.size(), level.decorated().size(), minY, maxY, timings);
	}

	private static long millisSince(long startNanos) {
		return (System.nanoTime() - startNanos) / 1_000_000L;
	}

	/**
	 * The generator that drives decoration.
	 *
	 * A session's registries deliberately do not contain dimensions:
	 * {@code RegistryDataLoader.WORLDGEN_REGISTRIES} covers biomes and
	 * features, while level stems live in DIMENSION_REGISTRIES, which a tree
	 * datapack has no reason to provide. So the running world's generator
	 * supplies the terrain behaviour, wrapped in {@link SessionGenerator} to
	 * redirect feature lookups at the session. That split is what keeps
	 * terrain real while still previewing the user's features.
	 */
	private ChunkGenerator generatorFor(RegistryAccess registries) {
		// lookup, not lookupOrThrow: the registry being absent entirely is the
		// normal case here, not an error.
		LevelStem stem = registries.lookup(Registries.LEVEL_STEM)
			.map(reg -> reg.getValue(LevelStem.OVERWORLD))
			.orElse(null);
		ChunkGenerator world = stem != null
			? stem.generator()
			: server.overworld().getChunkSource().getGenerator();

		return new SessionGenerator(world, registries);
	}

	/**
	 * Where a preview's time went, in milliseconds.
	 *
	 * Reported rather than guessed at, because the four phases scale very
	 * differently with area and which one dominates decides what is worth
	 * optimising next.
	 *
	 * @param generateMs  waiting for terrain to generate, margin included
	 * @param copyMs      snapshotting the generated chunks, on the server thread
	 * @param decorateMs  running the session's features over the snapshots
	 * @param emitMs      turning the result into block DTOs
	 * @param chunksTouched chunks generated, i.e. the grid plus its margin,
	 *                      which is what generateMs is really divided over
	 */
	public record Timings(
		long generateMs, long copyMs, long decorateMs, long emitMs, int chunksTouched) {

		@Override
		public String toString() {
			return "generate=" + generateMs + "ms (" + chunksTouched + " chunks)"
				+ " copy=" + copyMs + "ms"
				+ " decorate=" + decorateMs + "ms"
				+ " emit=" + emitMs + "ms";
		}
	}

	/**
	 * @param blocks        the preview geometry
	 * @param chunkCount    how many chunks were generated
	 * @param decoratedCount how many blocks decoration added, always reported
	 *                       so a preview that produced no trees is obvious
	 */
	public record Result(
		List<BlockDto> blocks, int chunkCount, int decoratedCount, int minY, int maxY,
		Timings timings) {
	}
}
