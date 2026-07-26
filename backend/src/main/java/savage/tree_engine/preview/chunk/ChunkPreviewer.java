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

		// Snapshotting reads live chunks, so it has to happen on the server
		// thread. Decoration afterwards works purely on the copies and stays
		// off it, keeping the game loop free.
		List<TerrainSnapshot> all = server.submit(() -> {
			List<TerrainSnapshot> out = new ArrayList<>();
			for (int x = minChunkX - MARGIN; x <= maxChunkX + MARGIN; x++) {
				for (int z = minChunkZ - MARGIN; z <= maxChunkZ + MARGIN; z++) {
					out.add(TerrainSnapshot.capture(server.overworld(), new ChunkPos(x, z)));
				}
			}
			return out;
		}).join();

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
			blocks = level.decorated();
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
