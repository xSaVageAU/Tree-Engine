package savage.tree_engine.preview.tree;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A generator that generates nothing.
 *
 * {@code ConfiguredFeature.place} requires a ChunkGenerator, and a handful of
 * features query it for terrain height. Since the single-tree preview stands
 * on a fabricated flat plane rather than real terrain, this reports that
 * plane and refuses to do anything else.
 *
 * Chunk previews use the world's real generator instead - this class is not
 * shared with them.
 */
final class FlatGenerator extends ChunkGenerator {
	FlatGenerator(BiomeSource biomeSource) {
		super(biomeSource);
	}

	@Override
	protected MapCodec<? extends ChunkGenerator> codec() {
		// Never serialized: this generator exists for the duration of one
		// preview request and is not part of any world's configuration.
		throw new UnsupportedOperationException("FlatGenerator is not serializable");
	}

	@Override
	public int getBaseHeight(int x, int z, Heightmap.Types type,
							 LevelHeightAccessor level, RandomState random) {
		return GroundPlane.SURFACE_Y + 1;
	}

	@Override
	public NoiseColumn getBaseColumn(int x, int z, LevelHeightAccessor level, RandomState random) {
		return new NoiseColumn(level.getMinY(), new BlockState[0]);
	}

	@Override
	public CompletableFuture<ChunkAccess> fillFromNoise(
		Blender blender, RandomState random, StructureManager structures, ChunkAccess chunk) {
		return CompletableFuture.completedFuture(chunk);
	}

	@Override
	public void applyCarvers(WorldGenRegion region, long seed, RandomState random,
							 BiomeManager biomes, StructureManager structures, ChunkAccess chunk) {
	}

	@Override
	public void buildSurface(WorldGenRegion region, StructureManager structures,
							 RandomState random, ChunkAccess chunk) {
	}

	@Override
	public void spawnOriginalMobs(WorldGenRegion region) {
	}

	@Override
	public void addDebugScreenInfo(List<String> info, RandomState random, BlockPos pos) {
	}

	@Override
	public int getGenDepth() {
		return 384;
	}

	@Override
	public int getSeaLevel() {
		return 63;
	}

	@Override
	public int getMinY() {
		return -64;
	}
}
