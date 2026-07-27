package savage.tree_engine.preview.chunk;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;

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
	private final int[] tops;
	private final ChunkAccess source;

	private TerrainSnapshot(
		ChunkPos pos, int minY, int height, BlockState[] blocks, int[] tops, ChunkAccess source) {
		this.pos = pos;
		this.minY = minY;
		this.height = height;
		this.blocks = blocks;
		this.tops = tops;
		this.source = source;
	}

	/**
	 * Copies an already-generated chunk. Callers hand in a chunk at SURFACE
	 * status - terrain and surface materials are present, but features have not
	 * been placed yet, which is exactly the state a preview wants to decorate.
	 *
	 * <p>Generation is deliberately not done here. It used to be, via
	 * {@code level.getChunk(..., true)}, which meant every chunk was generated
	 * one at a time on the server thread; {@link ChunkPreviewer} now requests
	 * them all up front so they generate in parallel. This method is only the
	 * copy, which is cheap.
	 *
	 * <p>Must be called on the server thread.
	 */
	public static TerrainSnapshot capture(ChunkAccess chunk) {
		ChunkPos pos = chunk.getPos();
		int minY = chunk.getMinY();
		int height = chunk.getHeight();

		BlockState[] blocks = new BlockState[16 * 16 * height];
		// Highest non-air block per column, recorded while we are already
		// walking every one of them. Free here, and it turns the level's
		// getHeight from a full-column scan into an array read - see
		// ChunkPreviewLevel#getHeight for why that matters so much.
		int[] tops = new int[16 * 16];
		java.util.Arrays.fill(tops, NO_BLOCKS);

		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
		for (int y = 0; y < height; y++) {
			for (int z = 0; z < 16; z++) {
				for (int x = 0; x < 16; x++) {
					cursor.set(pos.getMinBlockX() + x, minY + y, pos.getMinBlockZ() + z);
					BlockState state = chunk.getBlockState(cursor);
					blocks[index(x, y, z, height)] = state;
					// Ascending y, so the last write wins and ends up holding
					// the topmost non-air block.
					if (!state.isAir()) {
						tops[z * 16 + x] = minY + y;
					}
				}
			}
		}
		return new TerrainSnapshot(pos, minY, height, blocks, tops, chunk);
	}

	/** Marks a column of pure air, which has no top at all. */
	public static final int NO_BLOCKS = Integer.MIN_VALUE;

	/**
	 * The highest non-air block in a column, or {@link #NO_BLOCKS}.
	 * Terrain only - decoration written since the snapshot is the level's
	 * business, not this one's.
	 */
	public int topNonAir(int x, int z) {
		return tops[(z & 15) * 16 + (x & 15)];
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
