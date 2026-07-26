package savage.tree_engine.preview.tree;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import savage.tree_engine.api.ApiException;

/**
 * The fiction that makes a single-tree preview possible.
 *
 * A tree feature refuses to generate unless it is standing on something it
 * considers valid soil, and it asks the level what biome it is in. There is
 * no world here, so this fabricates both: an infinite soil plane just below
 * the origin, and one fixed biome.
 *
 * <p><b>This class is deliberately quarantined.</b> Natural chunk previews
 * must never touch it - they generate against real terrain and real biome
 * sources, and borrowing this fiction would silently make their output wrong
 * in ways that look plausible. Nothing outside {@code preview.tree} should
 * import it.
 */
public final class GroundPlane {
	/** Trees are placed at the origin, so the soil surface sits at y = -1. */
	public static final int SURFACE_Y = -1;

	private static final ResourceKey<Biome> DEFAULT_BIOME =
		ResourceKey.create(Registries.BIOME, Identifier.withDefaultNamespace("plains"));

	private final Holder<Biome> biome;

	private GroundPlane(Holder<Biome> biome) {
		this.biome = biome;
	}

	/**
	 * @param biomeId biome to report, or null for plains. Some trees vary
	 *                their output by biome, so the editor can pick one.
	 */
	public static GroundPlane create(RegistryAccess registries, String biomeId) {
		var biomes = registries.lookupOrThrow(Registries.BIOME);

		ResourceKey<Biome> key = DEFAULT_BIOME;
		if (biomeId != null && !biomeId.isBlank()) {
			Identifier parsed = Identifier.tryParse(biomeId);
			if (parsed == null) {
				throw ApiException.badRequest("Not a valid biome id: " + biomeId);
			}
			key = ResourceKey.create(Registries.BIOME, parsed);
		}

		ResourceKey<Biome> resolved = key;
		Holder<Biome> holder = biomes.get(resolved)
			.map(h -> (Holder<Biome>) h)
			.orElseThrow(() -> ApiException.badRequest("Unknown biome: " + resolved.identifier()));

		return new GroundPlane(holder);
	}

	public Holder<Biome> biome() {
		return biome;
	}

	/**
	 * What the fabricated world contains before the tree is placed: grass at
	 * the surface, dirt below it, air above.
	 */
	public BlockState blockAt(BlockPos pos) {
		if (pos.getY() == SURFACE_Y) {
			return Blocks.GRASS_BLOCK.defaultBlockState();
		}
		if (pos.getY() < SURFACE_Y) {
			return Blocks.DIRT.defaultBlockState();
		}
		return Blocks.AIR.defaultBlockState();
	}

	/** True for positions that are part of the fabricated ground, not the tree. */
	public boolean isGround(BlockPos pos) {
		return pos.getY() <= SURFACE_Y;
	}
}
