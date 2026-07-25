package savage.tree_engine.preview.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * An immutable copy of one real chunk's terrain.
 *
 * Natural previews decorate genuine terrain rather than the fabricated plane
 * the single-tree mode uses, so the shape of the ground, its surface blocks
 * and its biomes are all real. Taking a copy matters for two reasons: the
 * running world must never be mutated by a preview, and decoration runs on an
 * API worker thread where touching a live chunk would race the server.
 */
public final class TerrainSnapshot {
	private final ChunkPos pos;
	private final int minY;
	private final int height;
	private final BlockState[] blocks;
	private final ChunkAccess source;

	private TerrainSnapshot(ChunkPos pos, int minY, int height, BlockState[] blocks, ChunkAccess source) {
		this.pos = pos;
		this.minY = minY;
		this.height = height;
		this.blocks = blocks;
		this.source = source;
	}

	/**
	 * Copies a chunk at SURFACE status - terrain and surface materials are
	 * present, but features have not been placed yet, which is exactly the
	 * state a preview wants to decorate.
	 *
	 * <p>Must be called on the server thread.
	 */
	public static TerrainSnapshot capture(ServerLevel level, ChunkPos pos) {
		ChunkAccess chunk = level.getChunk(pos.x(), pos.z(), ChunkStatus.SURFACE, true);
		int minY = chunk.getMinY();
		int height = chunk.getHeight();

		BlockState[] blocks = new BlockState[16 * 16 * height];
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					cursor.set(pos.getMinBlockX() + x, minY + y, pos.getMinBlockZ() + z);
					blocks[index(x, y, z, height)] = chunk.getBlockState(cursor);
				}
			}
		}
		return new TerrainSnapshot(pos, minY, height, blocks, chunk);
	}

	public ChunkPos pos() {
		return pos;
	}

	public ChunkAccess sourceChunk() {
		return source;
	}

	public int minY() {
		return minY;
	}

	public int height() {
		return height;
	}

	public boolean contains(BlockPos p) {
		return p.getX() >> 4 == pos.x()
			&& p.getZ() >> 4 == pos.z()
			&& p.getY() >= minY
			&& p.getY() < minY + height;
	}

	public BlockState blockAt(BlockPos p) {
		if (!contains(p)) {
			return Blocks.AIR.defaultBlockState();
		}
		return blocks[index(p.getX() & 15, p.getY() - minY, p.getZ() & 15, height)];
	}

	public Holder<Biome> biomeAt(int x, int y, int z) {
		return source.getNoiseBiome(x, y, z);
	}

	private static int index(int x, int y, int z, int height) {
		return (y * 16 + z) * 16 + x;
	}
}
