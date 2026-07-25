package savage.tree_engine.datapack;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns "your datapack failed to load" into "this file is missing this field".
 *
 * Minecraft's registry loader reports which element failed but writes the
 * codec's actual complaint to its logger rather than carrying it in the
 * exception, so the reason never reaches the caller. When a load fails, this
 * re-parses the named file with its own codec purely to capture that error
 * and hand it back to the editor.
 */
public final class DatapackDiagnostics {
	/** Matches "…/worldgen/configured_feature/ns:path: Failed to parse …". */
	private static final Pattern FAILED_ELEMENT = Pattern.compile(
		"worldgen/(configured_feature|placed_feature)/([a-z0-9_.-]+:[a-z0-9_./-]+): Failed to parse");

	private DatapackDiagnostics() {
	}

	/**
	 * @param loaderDetail the message from the failed registry load
	 * @return a per-file explanation, or null if nothing more precise is available
	 */
	public static String explain(
		RegistryAccess registries, DatapackPayload payload, String loaderDetail) {

		if (loaderDetail == null || payload == null || payload.files() == null) {
			return null;
		}

		RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		List<String> explanations = new ArrayList<>();

		for (String element : failedElements(loaderDetail)) {
			String kind = element.substring(0, element.indexOf(' '));
			String id = element.substring(element.indexOf(' ') + 1);

			String path = pathFor(payload, kind, id);
			if (path == null) {
				continue;
			}
			String reason = parseError(ops, kind, payload.files().get(path));
			if (reason != null) {
				explanations.add(path + ": " + reason);
			}
		}

		return explanations.isEmpty() ? null : String.join("\n", explanations);
	}

	private static Set<String> failedElements(String detail) {
		Set<String> found = new LinkedHashSet<>();
		Matcher matcher = FAILED_ELEMENT.matcher(detail);
		while (matcher.find()) {
			found.add(matcher.group(1) + " " + matcher.group(2));
		}
		return found;
	}

	/** Maps a resource id back to the request path the client sent it as. */
	private static String pathFor(DatapackPayload payload, String kind, String id) {
		int colon = id.indexOf(':');
		String namespace = id.substring(0, colon);
		String name = id.substring(colon + 1);
		String expected = "data/" + namespace + "/worldgen/" + kind + "/" + name + ".json";

		for (String candidate : payload.files().keySet()) {
			if (candidate.replace('\\', '/').endsWith(expected)
				|| candidate.replace('\\', '/').equals(expected)) {
				return candidate;
			}
		}
		return null;
	}

	private static String parseError(RegistryOps<JsonElement> ops, String kind, String content) {
		if (content == null) {
			return null;
		}
		JsonElement json;
		try {
			json = JsonParser.parseString(content);
		} catch (Exception e) {
			return "not valid JSON (" + e.getMessage() + ")";
		}

		var result = switch (kind) {
			case "configured_feature" -> ConfiguredFeature.DIRECT_CODEC.parse(ops, json);
			case "placed_feature" -> PlacedFeature.DIRECT_CODEC.parse(ops, json);
			default -> null;
		};
		if (result == null) {
			return null;
		}
		// Only the failure is interesting. A file that parses cleanly here but
		// failed during the real load was rejected for a reason this cannot
		// see (an unresolved cross-reference), so it stays unreported rather
		// than guessed at.
		return result.error().map(e -> e.message()).orElse(null);
	}
}
