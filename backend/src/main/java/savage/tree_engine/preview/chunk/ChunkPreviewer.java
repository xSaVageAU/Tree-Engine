package savage.tree_engine.preview.chunk;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.LevelStem;
import savage.tree_engine.api.ApiException;
import savage.tree_engine.preview.BlockDto;

import java.util.ArrayList;
import java.util.List;

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
	 */
	private static final int MAX_CHUNKS = 9;

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
	 * How much ground to include beneath the lowest surface point. Enough to
	 * read the terrain as solid without dragging the whole stone column along.
	 */
	private static final int FLOOR_DEPTH = 4;

	/** Headroom above the tallest thing, so nothing is clipped at the top. */
	private static final int HEADROOM = 2;

	/**
	 * Ceiling on how tall an auto-fitted window may be.
	 *
	 * Across several chunks the terrain can span a valley and a hilltop, and
	 * anchoring the window at the lowest surface then fills every high column
	 * with solid rock the viewer cannot see into - measured at 74k blocks for a
	 * 3x3 before this cap, against 3k for a single flat chunk. Anchoring at the
	 * top instead keeps the surface and everything on it, and trades away the
	 * bottom of deep valleys. The window actually used is reported back, so the
	 * trade is visible rather than silent.
	 */
	private static final int MAX_WINDOW_HEIGHT = 48;

	public Result preview(
		RegistryAccess registries, int centerX, int centerZ, int radius,
		long seed, boolean fullChunk, Integer requestedMinY, Integer requestedMaxY) {

		int span = radius * 2 + 1;
		if (radius < 0) {
			throw ApiException.badRequest("radius must be >= 0");
		}
		if (span * span > MAX_CHUNKS) {
			throw ApiException.badRequest(
				"Requested " + (span * span) + " chunks; the limit is " + MAX_CHUNKS);
		}

		// Snapshotting reads live chunks, so it has to happen on the server
		// thread. Decoration afterwards works purely on the copies and stays
		// off it, keeping the game loop free.
		int outer = radius + MARGIN;
		List<TerrainSnapshot> all = server.submit(() -> {
			List<TerrainSnapshot> out = new ArrayList<>();
			for (int dx = -outer; dx <= outer; dx++) {
				for (int dz = -outer; dz <= outer; dz++) {
					out.add(TerrainSnapshot.capture(
						server.overworld(), new ChunkPos(centerX + dx, centerZ + dz)));
				}
			}
			return out;
		}).join();

		// Only the chunks the caller asked for get decorated and returned;
		// the margin exists purely so neighbour lookups resolve.
		List<TerrainSnapshot> requested = new ArrayList<>();
		for (TerrainSnapshot snapshot : all) {
			if (Math.abs(snapshot.pos().x() - centerX) <= radius
				&& Math.abs(snapshot.pos().z() - centerZ) <= radius) {
				requested.add(snapshot);
			}
		}

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

		// A chunk spans hundreds of blocks vertically and is nearly all stone.
		// Fitting the window to the surface is what makes this preview useful
		// rather than a rock column: it keeps the ground and everything
		// growing on it, and discards the rest before it hits the wire.
		int fromY;
		int toY;
		if (requestedMinY != null && requestedMaxY != null) {
			fromY = Math.min(requestedMinY, requestedMaxY);
			toY = Math.max(requestedMinY, requestedMaxY);
		} else {
			int lowestSurface = Integer.MAX_VALUE;
			int highest = Integer.MIN_VALUE;
			for (TerrainSnapshot snapshot : requested) {
				lowestSurface = Math.min(lowestSurface, level.lowestSurface(snapshot));
				highest = Math.max(highest, level.highestOccupied(snapshot));
			}
			toY = highest + HEADROOM;
			fromY = Math.max(lowestSurface - FLOOR_DEPTH, toY - MAX_WINDOW_HEIGHT);
		}

		List<BlockDto> blocks;
		if (fullChunk) {
			blocks = new ArrayList<>();
			for (TerrainSnapshot snapshot : requested) {
				blocks.addAll(level.fullChunk(snapshot, fromY, toY));
			}
		} else {
			blocks = level.decorated();
		}

		return new Result(blocks, requested.size(), level.decorated().size(), fromY, toY);
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
	 * @param blocks        the preview geometry
	 * @param chunkCount    how many chunks were generated
	 * @param decoratedCount how many blocks decoration added, always reported
	 *                       so a preview that produced no trees is obvious
	 */
	public record Result(
		List<BlockDto> blocks, int chunkCount, int decoratedCount, int minY, int maxY) {
	}
}
