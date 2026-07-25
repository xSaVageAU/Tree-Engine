package savage.tree_engine.preview.tree;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import savage.tree_engine.api.ApiException;
import savage.tree_engine.api.ApiServer;
import savage.tree_engine.api.Http;
import savage.tree_engine.datapack.SessionCache;

/**
 * {@code POST /v1/benchmark} - how expensive is this tree to generate?
 *
 * Useful when a config uses heavy placers or large foliage: the editor can
 * show that a tree costs 10x what a vanilla oak does before it ends up in a
 * datapack that generates thousands of them.
 */
public final class BenchmarkRoutes {
	/** Enough to be meaningful without letting a request occupy a worker for minutes. */
	private static final int MAX_ITERATIONS = 10_000;
	private static final int WARMUP = 50;

	private final SessionCache sessions;
	private final SingleTreePreviewer previewer;
	private final Gson gson;

	public BenchmarkRoutes(MinecraftServer server, SessionCache sessions, Gson gson) {
		this.sessions = sessions;
		this.previewer = new SingleTreePreviewer(server);
		this.gson = gson;
	}

	public void register(ApiServer api) {
		api.route("/v1/benchmark", this::handle);
	}

	private void handle(HttpExchange exchange) throws Exception {
		Http.require(exchange, "POST");

		JsonElement body = Http.readJson(exchange);
		if (!body.isJsonObject()) {
			throw ApiException.badRequest("Request body must be a JSON object");
		}
		JsonObject request = body.getAsJsonObject();

		JsonElement feature = request.get("feature");
		if (feature != null && feature.isJsonNull()) {
			feature = null;
		}
		String featureId = request.has("featureId") && !request.get("featureId").isJsonNull()
			? request.get("featureId").getAsString() : null;
		if (feature == null && featureId == null) {
			throw ApiException.badRequest("Provide either 'feature' or 'featureId'");
		}

		int iterations = request.has("iterations")
			? Math.clamp(request.get("iterations").getAsInt(), 1, MAX_ITERATIONS)
			: 1_000;

		String sessionId = request.has("sessionId") && !request.get("sessionId").isJsonNull()
			? request.get("sessionId").getAsString() : null;
		RegistryAccess registries = sessionId != null && !sessionId.isBlank()
			? sessions.require(sessionId).registries()
			: previewer.serverRegistries();

		String biome = request.has("biome") && !request.get("biome").isJsonNull()
			? request.get("biome").getAsString() : null;

		// Warm up first: the first few runs pay class-loading and JIT costs
		// that would otherwise dominate a short benchmark and make every
		// config look equally slow.
		for (int i = 0; i < WARMUP; i++) {
			previewer.preview(registries, feature, featureId, biome, i, false);
		}

		int blocks = 0;
		long start = System.nanoTime();
		for (int i = 0; i < iterations; i++) {
			// Vary the seed so the benchmark covers the config's range of
			// outcomes rather than measuring one shape repeatedly.
			blocks += previewer.preview(registries, feature, featureId, biome, i, false)
				.blocks().size();
		}
		long elapsedNanos = System.nanoTime() - start;

		double totalMs = elapsedNanos / 1_000_000.0;
		JsonObject response = new JsonObject();
		response.addProperty("iterations", iterations);
		response.addProperty("totalMs", totalMs);
		response.addProperty("avgMs", totalMs / iterations);
		response.addProperty("treesPerSecond", iterations / (totalMs / 1000.0));
		response.addProperty("avgBlocks", (double) blocks / iterations);
		Http.sendJson(exchange, 200, gson.toJson(response));
	}
}
