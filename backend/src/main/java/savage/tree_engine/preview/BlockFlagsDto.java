package savage.tree_engine.preview;

import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-block rendering hints, derived from the game's own block behaviour.
 *
 * The renderer needs to know three things about a block that a flat list of
 * placed blockstates cannot tell it: whether it hides the faces of whatever is
 * behind it, whether it is see-through (and so must be drawn after everything
 * solid), and whether it hides its own faces against itself. Without them the
 * renderer treats every block as an opaque, depth-writing cube - which is why
 * a body of water used to blot out the terrain underneath it: the water was
 * drawn first, wrote depth, and the ground behind failed the depth test.
 *
 * These are keyed by block name, not blockstate, because that is the
 * granularity the renderer asks at - so the block's default state is the right
 * thing to inspect.
 */
public record BlockFlagsDto(boolean opaque, boolean semiTransparent, boolean selfCulling) {

	/** Flags for each distinct block name appearing in {@code blocks}. */
	public static Map<String, BlockFlagsDto> forBlocks(List<BlockDto> blocks) {
		Map<String, BlockFlagsDto> out = new LinkedHashMap<>();
		for (BlockDto block : blocks) {
			out.computeIfAbsent(block.name(), BlockFlagsDto::forName);
		}
		return out;
	}

	private static BlockFlagsDto forName(String name) {
		Block block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(name)).orElse(null);
		if (block == null) {
			// Unknown to this Minecraft version - the renderer will not have a
			// model for it either, so all-false (draw everything, cull nothing)
			// is the safe answer.
			return new BlockFlagsDto(false, false, false);
		}
		BlockState state = block.defaultBlockState();

		// skipRendering() against itself is vanilla's own "these two faces
		// cancel out" test, and the blocks that override it are exactly the
		// see-through family: glass, tinted glass, ice, honey, slime. Together
		// with fluids that is the set that has to be drawn last and blended.
		boolean skipsAgainstSelf = state.skipRendering(state, Direction.UP);
		boolean fluid = !state.getFluidState().isEmpty();

		return new BlockFlagsDto(state.isSolidRender(), fluid || skipsAgainstSelf, skipsAgainstSelf || fluid);
	}
}
