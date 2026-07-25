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
	 * How thick the terrain crust is: how far below its own surface each
	 * column is carried down. Enough to read as solid ground without dragging
	 * the whole stone column along.
	 */
	private static final int CRUST_DEPTH = 5;

	/** Headroom above the tallest thing, so nothing is clipped at the top. */
	private static final int HEADROOM = 2;

	/**
	 * Hard floor on how far below the top of the preview anything is emitted.
	 *
	 * This is not the mechanism that bounds the block count - the per-column
	 * crust does that. It exists for pathological columns: a shaft or cave
	 * mouth whose "surface" is a hundred blocks down would otherwise stretch
	 * the renderer's bounding box over mostly empty space. Set far enough
	 * below normal terrain variation that it never cuts a hillside, which is
	 * exactly what an earlier and much tighter cap did.
	 */
	private static final int MAX_SPAN = 64;

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
		// What a preview wants is the surface and whatever grows on it, so
		// each column is carried down a fixed depth from its own surface and
		// no further - see fullChunk.
		int crust;
		int toY;
		int hardFloor;
		if (requestedMinY != null && requestedMaxY != null) {
			// An explicit window is taken literally: the caller asked for a
			// slab, so give them a slab.
			int lo = Math.min(requestedMinY, requestedMaxY);
			toY = Math.max(requestedMinY, requestedMaxY);
			crust = Math.max(0, toY - lo);
			hardFloor = lo;
		} else {
			int highest = Integer.MIN_VALUE;
			for (TerrainSnapshot snapshot : requested) {
				highest = Math.max(highest, level.highestOccupied(snapshot));
			}
			toY = highest + HEADROOM;
			crust = CRUST_DEPTH;
			hardFloor = toY - MAX_SPAN;
		}

		List<BlockDto> blocks;
		if (fullChunk) {
			blocks = new ArrayList<>();
			for (TerrainSnapshot snapshot : requested) {
				blocks.addAll(level.fullChunk(snapshot, crust, toY, hardFloor));
			}
			// Decoration that reached past the requested chunks comes along,
			// so a tree on a border is not sliced in half.
			java.util.Set<Long> requestedKeys = new java.util.HashSet<>();
			for (TerrainSnapshot snapshot : requested) {
				requestedKeys.add(ChunkPos.pack(snapshot.pos().x(), snapshot.pos().z()));
			}
			blocks.addAll(level.spillOutside(requestedKeys, hardFloor, toY));
		} else {
			blocks = level.decorated();
		}

		// Report the window the output actually occupies rather than the one
		// asked for - with a per-column floor there is no single lower plane.
		int minY = Integer.MAX_VALUE;
		int maxY = Integer.MIN_VALUE;
		for (BlockDto block : blocks) {
			minY = Math.min(minY, block.y());
			maxY = Math.max(maxY, block.y());
		}
		if (blocks.isEmpty()) {
			minY = 0;
			maxY = 0;
		}

		return new Result(blocks, requested.size(), level.decorated().size(), minY, maxY);
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
