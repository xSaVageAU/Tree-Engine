package savage.tree_engine.preview.chunk;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * The world's generator, with one thing changed: which features each biome
 * generates.
 *
 * This is what makes a datapack visible in a natural preview. Decoration
 * resolves a biome's feature list through
 * {@link ChunkGenerator#getBiomeGenerationSettings}, and so does
 * {@code BiomeFilter}, the placement modifier that rejects a feature which
 * does not belong to the biome it landed in. Both read the single function
 * this class supplies, so pointing that function at the session's registries
 * redirects the feature lists and the validation together.
 *
 * <p>Consistency is the whole trick. An earlier attempt swapped only the
 * biome objects handed back by the level, which left decoration iterating the
 * world's features while the filter checked the session's - every feature
 * failed and previews came out empty. Routing both through one function is
 * what avoids that.
 *
 * <p>Terrain generation is untouched and delegates to the real generator: a
 * preview shows the user's trees on the world's actual ground.
 */
final class SessionGenerator extends ChunkGenerator {
	private final ChunkGenerator delegate;

	SessionGenerator(ChunkGenerator delegate, RegistryAccess session) {
		super(delegate.getBiomeSource(), holder -> settingsFrom(session, holder));
		this.delegate = delegate;
	}

	/**
	 * The session's definition of a biome, falling back to the world's for any
	 * biome the datapack does not override.
	 */
	private static BiomeGenerationSettings settingsFrom(RegistryAccess session, Holder<Biome> holder) {
		return holder.unwrapKey()
			.flatMap(key -> session.lookupOrThrow(Registries.BIOME).get(key))
			.map(entry -> entry.value().getGenerationSettings())
			.orElseGet(() -> holder.value().getGenerationSettings());
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		// Exists for one preview request; never written to a world's config.
		throw new UnsupportedOperationException("SessionGenerator is not serializable");
	}

	// --- everything below is the real generator's behaviour ---------------

	@Override
	public void applyCarvers(WorldGenRegion region, long seed, RandomState random,
							 BiomeManager biomes, StructureManager structures, ChunkAccess chunk) {
		delegate.applyCarvers(region, seed, random, biomes, structures, chunk);
	}

	@Override
	public void buildSurface(WorldGenRegion region, StructureManager structures,
							 RandomState random, ChunkAccess chunk) {
		delegate.buildSurface(region, structures, random, chunk);
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
		delegate.spawnOriginalMobs(region);
	}

	@Override
	public int getGenDepth() {
		return delegate.getGenDepth();
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(
		Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
		return delegate.fillFromNoise(blender, random, structures, chunk);
	}

	@Override
	public int getSeaLevel() {
		return delegate.getSeaLevel();
	}

	@Override
	public int getMinY() {
		return delegate.getMinY();
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type,
							 LevelHeightAccessor level, RandomState random) {
		return delegate.getBaseHeight(x, z, type, level, random);
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
		return delegate.getBaseColumn(x, z, level, random);
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
		delegate.addDebugScreenInfo(info, random, pos);
	}
}
