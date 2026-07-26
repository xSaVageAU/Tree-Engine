package savage.tree_engine.registry;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.TreeFeature;
import savage.tree_engine.api.ApiException;
import savage.tree_engine.api.ApiServer;
import savage.tree_engine.api.Http;
import savage.tree_engine.datapack.SessionCache;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Read-only views of what a session's registries contain.
 *
 * The editor needs these to populate its browsers without the frontend
 * hardcoding any Minecraft data - ids come from the game, which is also why
 * there is no hand-maintained list of vanilla trees anywhere in this project.
 */
public final class RegistryRoutes {
	private final MinecraftServer server;
	private final SessionCache sessions;
	private final Gson gson;

	public RegistryRoutes(MinecraftServer server, SessionCache sessions, Gson gson) {
		this.server = server;
		this.sessions = sessions;
		this.gson = gson;
	}

	public void register(ApiServer api) {
		api.route("/v1/registry/features", this::listFeatures);
		api.route("/v1/registry/feature/", this::getFeature);
	}

	/**
	 * {@code GET /v1/registry/feature/{id}}
	 *
	 * The feature's datapack JSON, encoded from the live registry. This is how
	 * the editor imports a vanilla tree and how it seeds a new one: the
	 * starting JSON is whatever the game says it is, so it is correct for the
	 * running version by construction rather than by a literal someone has to
	 * keep up to date.
	 *
	 * <p>The id may contain slashes (some packs nest feature ids), so it is
	 * taken as the whole path tail rather than a single segment.
	 */
	private void getFeature(HttpExchange exchange) throws Exception {
		Http.require(exchange, "GET");

		String raw = Http.tail(exchange, "/v1/registry/feature/");
		int query = raw.indexOf('?');
		String id = query >= 0 ? raw.substring(0, query) : raw;

		var params = Query.of(exchange.getRequestURI().getRawQuery());
		String sessionId = params.get("sessionId");
		RegistryAccess registries = sessionId != null
			? sessions.require(sessionId).registries()
			: server.registryAccess();

		Identifier parsed = Identifier.tryParse(id);
		if (parsed == null) {
			throw ApiException.badRequest("Not a valid feature id: " + id);
		}

		ConfiguredFeature<?, ?> feature = registries
			.lookupOrThrow(Registries.CONFIGURED_FEATURE)
			.getValue(ResourceKey.create(Registries.CONFIGURED_FEATURE, parsed));
		if (feature == null) {
			throw ApiException.notFound("No such configured feature: " + id);
		}

		// DIRECT_CODEC emits the inline definition; CODEC would emit a
		// reference to the id we just looked up, which is useless to an editor.
		RegistryOps<JsonElement> ops = registries.createSerializationContext(JsonOps.INSTANCE);
		JsonElement encoded = ConfiguredFeature.DIRECT_CODEC.encodeStart(ops, feature)
			.getOrThrow(error -> ApiException.internal(
				"Failed to encode " + id, new IllegalStateException(error)));

		Http.sendJson(exchange, 200, gson.toJson(encoded));
	}

	/**
	 * {@code GET /v1/registry/features?sessionId=...&trees=true}
	 *
	 * Lists configured feature ids. {@code trees=true} narrows the result to
	 * features that actually generate trees, which is what the tree browser
	 * wants; the unfiltered list is everything the session can generate.
	 */
	private void listFeatures(HttpExchange exchange) throws Exception {
		Http.require(exchange, "GET");

		var query = Query.of(exchange.getRequestURI().getRawQuery());
		String sessionId = query.get("sessionId");
		boolean treesOnly = Boolean.parseBoolean(query.get("trees"));

		RegistryAccess registries = sessionId != null
			? sessions.require(sessionId).registries()
			: server.registryAccess();

		List<String> ids = new ArrayList<>();
		for (var entry : registries.lookupOrThrow(Registries.CONFIGURED_FEATURE).entrySet()) {
			ConfiguredFeature<?, ?> feature = entry.getValue();
			if (treesOnly && !(feature.feature() instanceof TreeFeature)) {
				continue;
			}
			ids.add(entry.getKey().identifier().toString());
		}
		Collections.sort(ids);

		JsonArray array = new JsonArray();
		ids.forEach(array::add);

		JsonObject body = new JsonObject();
		body.add("features", array);
		body.addProperty("count", ids.size());
		Http.sendJson(exchange, 200, gson.toJson(body));
	}

	/** Minimal query-string parsing; the API has only a couple of parameters. */
	private record Query(java.util.Map<String, String> values) {
		static Query of(String raw) {
			java.util.Map<String, String> out = new java.util.HashMap<>();
			if (raw != null) {
				for (String pair : raw.split("&")) {
					int eq = pair.indexOf('=');
					if (eq > 0) {
						out.put(
							java.net.URLDecoder.decode(pair.substring(0, eq), java.nio.charset.StandardCharsets.UTF_8),
							java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8));
					}
				}
			}
			return new Query(out);
		}

		String get(String key) {
			String value = values.get(key);
			return value == null || value.isBlank() ? null : value;
		}
	}
}
