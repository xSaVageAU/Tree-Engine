package savage.tree_engine.datapack;

import net.minecraft.ReportedException;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.RegistryLayer;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.CloseableResourceManager;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import savage.tree_engine.api.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Compiles a datapack into a usable {@link RegistryAccess} without touching
 * disk. This is the foundation both preview modes are built on, and the only
 * place in the backend that knows how registry loading works.
 */
public final class RegistrySet {
	private RegistrySet() {
	}

	/**
	 * Builds a frozen registry set containing vanilla's data with the given
	 * pack layered on top.
	 *
	 * Two details here are load-bearing and were established by running them,
	 * so do not "simplify" either one:
	 *
	 * <ol>
	 *   <li>The pack is <em>appended to</em> the server's existing packs, not
	 *       used alone. {@code WORLDGEN_REGISTRIES} loads every worldgen
	 *       registry in one pass, and several of them ({@code pig_variant},
	 *       {@code wolf_variant}, the sound variants) fail a non-empty
	 *       validator if vanilla's own data is missing. Layering is also the
	 *       semantics we want: the user's datapack overrides vanilla.</li>
	 *   <li>The base lookups come from the layer <em>below</em> worldgen.
	 *       Passing the worldgen layer itself would duplicate the very
	 *       registries being loaded.</li>
	 * </ol>
	 */
	public static RegistryAccess.Frozen compile(
		MinecraftServer server, PackResources pack, Executor executor) {

		List<PackResources> packs =
			new ArrayList<>(server.getResourceManager().listPacks().toList());
		packs.add(pack);

		try (CloseableResourceManager resources =
				 new MultiPackResourceManager(PackType.SERVER_DATA, packs)) {

			List<HolderLookup.RegistryLookup<?>> base = server.registries()
				.getAccessForLoading(RegistryLayer.WORLDGEN)
				.listRegistries()
				.toList();

			return RegistryDataLoader.load(
				resources, base, RegistryDataLoader.WORLDGEN_REGISTRIES, executor).join();

		} catch (Exception e) {
			throw ApiException.badRequest("Datapack failed to load", describe(e));
		}
	}

	/**
	 * Registry load failures arrive wrapped in a CrashReport whose stack
	 * trace says nothing useful; the per-element codec errors live in the
	 * report details. Surfacing those is the difference between "datapack
	 * failed to load" and "no key below_trunk_provider in my_oak.json".
	 */
	private static String describe(Throwable t) {
		for (Throwable c = t; c != null; c = c.getCause()) {
			if (c instanceof ReportedException reported) {
				String which = trimToErrors(reported.getReport().getDetails());
				String why = rootMessage(c);
				// The report names the element that failed; the root cause
				// carries the codec's reason. Neither is sufficient alone.
				if (why != null && which != null && !which.contains(why)) {
					return which + "\n" + why;
				}
				return which != null ? which : why;
			}
		}
		return rootMessage(t);
	}

	private static String rootMessage(Throwable t) {
		Throwable deepest = t;
		while (deepest.getCause() != null && deepest.getCause() != deepest) {
			deepest = deepest.getCause();
		}
		return deepest.getMessage();
	}

	/**
	 * Strips the crash-report preamble (thread name, stack frames inside the
	 * loader) and keeps the per-element codec errors, which are the only part
	 * that tells the user what is wrong with their datapack.
	 */
	private static String trimToErrors(String details) {
		if (details == null) {
			return null;
		}
		int start = details.indexOf("Errors:");
		String body = start >= 0 ? details.substring(start + "Errors:".length()) : details;

		// Everything from the environment dump onward is noise for a
		// datapack author.
		int systemDetails = body.indexOf("-- System Details --");
		if (systemDetails >= 0) {
			body = body.substring(0, systemDetails);
		}

		// Drop stack frames. What remains is the element that failed and the
		// codec's reason ("No key below_trunk_provider in ..."), which is the
		// only part a user can act on.
		StringBuilder out = new StringBuilder();
		for (String line : body.split("\n")) {
			String trimmed = line.strip();
			if (trimmed.isEmpty()
				|| trimmed.startsWith("at ")
				|| trimmed.startsWith("...")
				|| trimmed.equals("Stacktrace:")) {
				continue;
			}
			if (trimmed.startsWith("Caused by: ")) {
				trimmed = trimmed.substring("Caused by: ".length());
			}
			// Codec errors arrive as "java.lang.IllegalStateException: msg";
			// the exception class adds nothing for the reader.
			int marker = trimmed.indexOf("Exception: ");
			if (marker > 0 && trimmed.lastIndexOf(' ', marker) < 0) {
				trimmed = trimmed.substring(marker + "Exception: ".length());
			}
			if (!out.isEmpty()) {
				out.append('\n');
			}
			out.append(trimmed);
		}

		// Registry loading reports every failing element; a datapack with one
		// broken file should not return kilobytes of repeated context.
		String result = out.toString();
		int limit = 4000;
		return result.length() > limit
			? result.substring(0, limit) + "\n... (truncated)"
			: result;
	}
}
