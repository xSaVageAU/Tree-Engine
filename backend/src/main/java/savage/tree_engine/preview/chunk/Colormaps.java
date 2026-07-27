package savage.tree_engine.preview.chunk;

import net.minecraft.world.level.DryFoliageColor;
import net.minecraft.world.level.FoliageColor;
import net.minecraft.world.level.GrassColor;
import savage.tree_engine.api.ApiServer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Teaches a Minecraft <em>server</em> what colour grass is.
 *
 * <p>{@code Biome.getGrassColor} and friends look their answer up in a 256x256
 * colormap texture indexed by temperature and downfall. Those textures are
 * client assets, and the class that holds them - {@link GrassColor} and the two
 * beside it - is populated by {@code init(int[])} during client resource
 * loading. A dedicated server never calls it, so the arrays stay zeroed and
 * every biome colour comes back as pure black. That is not a fallback; it is
 * simply a code path the server was never expected to reach.
 *
 * <p>The launcher already extracts these textures for the renderer, so the fix
 * is to point the backend at them and call the same init the client would.
 * Everything downstream - the climate lookup, the swamp's noise-modulated
 * grass, dark forest's darkening - is then vanilla's own code producing
 * vanilla's own numbers.
 *
 * <p>Loaded lazily rather than at startup: the launcher writes the path before
 * the assets are necessarily extracted, and nothing needs a biome colour until
 * the first world preview, by which point they are certainly there.
 */
public final class Colormaps {
	private static boolean attempted;
	private static boolean loaded;

	private Colormaps() {
	}

	/**
	 * Ensures the colormaps are loaded, once.
	 *
	 * @return whether biome colours can be trusted. False means previews will
	 *         come back black, which callers may want to say out loud.
	 */
	public static synchronized boolean ensureLoaded(String colormapsDir) {
		if (attempted) {
			return loaded;
		}
		attempted = true;

		if (colormapsDir == null || colormapsDir.isBlank()) {
			ApiServer.LOGGER.warn(
				"No colormaps directory configured; biome colours will be black. "
					+ "The launcher normally supplies this.");
			return false;
		}

		Path dir = Path.of(colormapsDir);
		try {
			GrassColor.init(readColormap(dir.resolve("grass.png")));
			FoliageColor.init(readColormap(dir.resolve("foliage.png")));
			DryFoliageColor.init(readColormap(dir.resolve("dry_foliage.png")));
			loaded = true;
			ApiServer.LOGGER.info("Loaded biome colormaps from {}", dir);
		} catch (IOException e) {
			ApiServer.LOGGER.warn(
				"Could not load biome colormaps from {}; biome colours will be black", dir, e);
		}
		return loaded;
	}

	/**
	 * Reads a colormap as the packed 0xRRGGBB array the game expects.
	 *
	 * <p>The game's own loader takes pixels from a NativeImage; ImageIO's
	 * TYPE_INT_RGB getRGB gives the same layout, and the alpha channel these
	 * textures carry is not part of the lookup.
	 */
	private static int[] readColormap(Path path) throws IOException {
		if (!Files.exists(path)) {
			throw new IOException("colormap not found: " + path);
		}
		BufferedImage image = ImageIO.read(path.toFile());
		if (image == null) {
			throw new IOException("colormap is not a readable image: " + path);
		}
		int width = image.getWidth();
		int height = image.getHeight();
		int[] pixels = new int[width * height];
		image.getRGB(0, 0, width, height, pixels, 0, width);
		for (int i = 0; i < pixels.length; i++) {
			pixels[i] &= 0xFFFFFF;
		}
		return pixels;
	}
}
