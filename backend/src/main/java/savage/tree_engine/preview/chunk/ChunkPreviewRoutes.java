package savage.tree_engine.preview.chunk;

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
import savage.tree_engine.preview.BlockFlagsDto;

/**
 * {@code POST /v1/preview/chunk} - generate real terrain decorated with the
 * session's datapack features.
 */
public final class ChunkPreviewRoutes {
	private final MinecraftServer server;
	private final SessionCache sessions;
	private final ChunkPreviewer previewer;
	private final Gson gson;

	public ChunkPreviewRoutes(MinecraftServer server, SessionCache sessions, Gson gson) {
		this.server = server;
		this.sessions = sessions;
		this.previewer = new ChunkPreviewer(server);
		this.gson = gson;
	}

	public void register(ApiServer api) {
		api.route("/v1/preview/chunk", this::handle);
	}

	private void handle(HttpExchange exchange) throws Exception {
		Http.require(exchange, "POST");

		JsonElement body = Http.readJson(exchange);
		if (!body.isJsonObject()) {
			throw ApiException.badRequest("Request body must be a JSON object");
		}
		JsonObject request = body.getAsJsonObject();

		// Unlike single-tree previews a session is the whole point here:
		// without a datapack this would just render vanilla.
		RegistryAccess registries;
		String sessionId = string(request, "sessionId");
		registries = sessionId != null
			? sessions.require(sessionId).registries()
			: server.registryAccess();

		ChunkPreviewer.Result result = previewer.preview(
			registries,
			intOr(request, "chunkX", 0),
			intOr(request, "chunkZ", 0),
			chunkSpan(request),
			request.has("seed") ? request.get("seed").getAsLong() : 0L,
			!request.has("decoratedOnly") || !request.get("decoratedOnly").getAsBoolean(),
			intOrNull(request, "minY"),
			intOrNull(request, "maxY"));

		JsonObject response = new JsonObject();
		response.add("blocks", gson.toJsonTree(result.blocks()));
		response.add("blockFlags", gson.toJsonTree(BlockFlagsDto.forBlocks(result.blocks())));
		response.addProperty("blockCount", result.blocks().size());
		response.addProperty("chunkCount", result.chunkCount());
		response.addProperty("decoratedCount", result.decoratedCount());
		response.addProperty("datapackApplied", sessionId != null);
		// Where the server's time actually went, so a slow preview can be
		// attributed rather than guessed at.
		response.add("timings", gson.toJsonTree(result.timings()));
		// The vertical window actually used, so a client can frame the camera
		// without guessing where the ground is.
		response.addProperty("minY", result.minY());
		response.addProperty("maxY", result.maxY());
		Http.sendJson(exchange, 200, gson.toJson(response));
	}

	/**
	 * How many chunks across the preview should be.
	 *
	 * `size` is the direct form (1 = one chunk, 2 = 2x2, 3 = 3x3). `radius` is
	 * accepted for older callers, where 0 meant one chunk and 1 meant 3x3 -
	 * which could not express an even span at all, and 2x2 is a useful middle
	 * step between one chunk and nine.
	 */
	private static int chunkSpan(JsonObject request) {
		Integer size = intOrNull(request, "size");
		if (size != null) {
			return size;
		}
		return intOr(request, "radius", 0) * 2 + 1;
	}

	private static Integer intOrNull(JsonObject object, String key) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? null : value.getAsInt();
	}

	private static int intOr(JsonObject object, String key, int fallback) {
		JsonElement value = object.get(key);
		return value == null || value.isJsonNull() ? fallback : value.getAsInt();
	}

	private static String string(JsonObject object, String key) {
		JsonElement value = object.get(key);
		if (value == null || value.isJsonNull()) {
			return null;
		}
		String text = value.getAsString();
		return text.isBlank() ? null : text;
	}
}
