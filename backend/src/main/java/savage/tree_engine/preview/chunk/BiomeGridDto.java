package savage.tree_engine.preview.chunk;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Which biome each column of a preview is in, and what colours that biome
 * tints things.
 *
 * <p>The colours are read from the game's own {@link Biome} rather than
 * approximated in the renderer. That matters for two reasons: a datapack can
 * define biomes the renderer has never heard of, and the whole point of this
 * preview is to show what the game would actually draw. The renderer's job is
 * to multiply a texture by a number it was given, not to know what a swamp
 * looks like.
 *
 * <p>Resolution is one entry per block column. Minecraft itself only stores
 * biomes per 4x4x4 cell, so neighbouring columns often share an entry - but
 * emitting per column keeps the client's indexing trivial and costs little
 * once the palette does the deduplicating.
 */
public record BiomeGridDto(
	int originX, int originZ, int width, int depth,
	List<Entry> palette, int[] columns, String center) {

	/**
	 * One distinct biome in the preview.
	 *
	 * @param grass      grass tint, 0xRRGGBB
	 * @param foliage    leaf/vine tint
	 * @param dryFoliage the tint used by leaf litter and other dried plants
	 * @param water      water tint
	 */
	public record Entry(
		String name, int grass, int foliage, int dryFoliage, int water) {
	}

	/** Accumulates columns into a palette, so callers do not have to. */
	public static final class Builder {
		private final int originX;
		private final int originZ;
		private final int width;
		private final int depth;
		private final int[] columns;
		private final List<Entry> palette = new ArrayList<>();
		// Keyed by biome name: two holders for the same biome are equal for
		// colouring purposes even when they are not the same object.
		private final Map<String, Integer> indexByName = new HashMap<>();
		private String center = "";

		public Builder(int originX, int originZ, int width, int depth) {
			this.originX = originX;
			this.originZ = originZ;
			this.width = width;
			this.depth = depth;
			this.columns = new int[width * depth];
		}

		/**
		 * Records the biome of one column.
		 *
		 * @param sampleX world x to sample the grass colour at - swamps vary
		 *                theirs by noise within a single biome, so the position
		 *                is not decoration
		 */
		public void set(int x, int z, Holder<Biome> biome, int sampleX, int sampleZ) {
			columns[(z - originZ) * width + (x - originX)] = index(biome, sampleX, sampleZ);
		}

		public void setCenter(Holder<Biome> biome) {
			this.center = nameOf(biome);
		}

		private int index(Holder<Biome> biome, int sampleX, int sampleZ) {
			String name = nameOf(biome);
			Integer existing = indexByName.get(name);
			if (existing != null) {
				return existing;
			}
			Biome value = biome.value();
			int index = palette.size();
			palette.add(new Entry(
				name,
				value.getGrassColor(sampleX, sampleZ) & 0xFFFFFF,
				value.getFoliageColor() & 0xFFFFFF,
				value.getDryFoliageColor() & 0xFFFFFF,
				value.getWaterColor() & 0xFFFFFF));
			indexByName.put(name, index);
			return index;
		}

		private static String nameOf(Holder<Biome> biome) {
			return biome.unwrapKey()
				.<String>map(key -> key.identifier().toString())
				// An unregistered biome has no name to show, but it still has
				// colours worth using, so this is not an error.
				.orElse("unknown");
		}

		public BiomeGridDto build() {
			return new BiomeGridDto(originX, originZ, width, depth, palette, columns, center);
		}
	}
}
