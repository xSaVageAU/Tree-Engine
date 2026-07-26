package savage.tree_engine.preview;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One placed block, as the renderer consumes it.
 *
 * This is the <em>only</em> thing the single-tree and natural-chunk preview
 * modes share. They agree on an output format and nothing else - no shared
 * level implementation, no shared generation path.
 */
public record BlockDto(int x, int y, int z, String name, Map<String, String> properties) {

	public static BlockDto of(int x, int y, int z, BlockState state) {
		return new BlockDto(x, y, z, idOf(state), propertiesOf(state));
	}

	private static String idOf(BlockState state) {
		return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
	}

	/**
	 * Blockstate properties as strings, matching the datapack/NBT convention
	 * the renderer already speaks. Returns null rather than an empty map so
	 * the common case costs nothing on the wire.
	 */
	private static Map<String, String> propertiesOf(BlockState state) {
		if (state.getProperties().isEmpty()) {
			return null;
		}
		Map<String, String> out = new LinkedHashMap<>();
		for (Property<?> property : state.getProperties()) {
			out.put(property.getName(), stringify(state, property));
		}
		return out;
	}

	private static <T extends Comparable<T>> String stringify(BlockState state, Property<T> property) {
		return property.getName(state.getValue(property));
	}
}
