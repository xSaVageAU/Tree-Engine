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

	public Result preview(
		RegistryAccess registries, int centerX, int centerZ, int radius,
		long seed, boolean fullChunk) {

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

		List<BlockDto> blocks;
		if (fullChunk) {
			blocks = new ArrayList<>();
			for (TerrainSnapshot snapshot : requested) {
				blocks.addAll(level.fullChunk(snapshot));
			}
		} else {
			blocks = level.decorated();
		}

		return new Result(blocks, requested.size(), level.decorated().size());
	}

	/**
	 * The generator that drives decoration.
	 *
	 * A session's registries deliberately do not contain dimensions:
	 * {@code RegistryDataLoader.WORLDGEN_REGISTRIES} covers biomes and
	 * features, while level stems live in DIMENSION_REGISTRIES, which a tree
	 * datapack has no reason to provide. So the running world's generator is
	 * used for the mechanics of decoration, and the datapack's influence
	 * arrives through the biomes {@link ChunkPreviewLevel} hands back - see
	 * the biome remapping there. That split is what keeps terrain real while
	 * still previewing the user's features.
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
	public record Result(List<BlockDto> blocks, int chunkCount, int decoratedCount) {
	}
}
