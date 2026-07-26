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
import savage.tree_engine.datapack.Session;
import savage.tree_engine.datapack.SessionCache;
import savage.tree_engine.preview.BlockFlagsDto;

/**
 * {@code POST /v1/preview/tree} - generate a single tree in isolation.
 */
public final class TreePreviewRoutes {
	private final SessionCache sessions;
	private final SingleTreePreviewer previewer;
	private final Gson gson;

	public TreePreviewRoutes(MinecraftServer server, SessionCache sessions, Gson gson) {
		this.sessions = sessions;
		this.previewer = new SingleTreePreviewer(server);
		this.gson = gson;
	}

	public void register(ApiServer api) {
		api.route("/v1/preview/tree", this::handle);
	}

	private void handle(HttpExchange exchange) throws Exception {
		Http.require(exchange, "POST");

		JsonElement body = Http.readJson(exchange);
		if (!body.isJsonObject()) {
			throw ApiException.badRequest("Request body must be a JSON object");
		}
		JsonObject request = body.getAsJsonObject();

		// A sessionId is optional: previewing an inline feature that only
		// references vanilla blocks needs no custom datapack at all.
		RegistryAccess registries;
		String sessionId = string(request, "sessionId");
		if (sessionId != null) {
			Session session = sessions.require(sessionId);
			registries = session.registries();
		} else {
			registries = previewer.serverRegistries();
		}

		JsonElement feature = request.get("feature");
		if (feature != null && feature.isJsonNull()) {
			feature = null;
		}

		SingleTreePreviewer.Result result = previewer.preview(
			registries,
			feature,
			string(request, "featureId"),
			string(request, "biome"),
			request.has("seed") ? request.get("seed").getAsLong() : 0L,
			request.has("includeGround") && request.get("includeGround").getAsBoolean());

		JsonObject response = new JsonObject();
		response.add("blocks", gson.toJsonTree(result.blocks()));
		response.add("blockFlags", gson.toJsonTree(BlockFlagsDto.forBlocks(result.blocks())));
		response.addProperty("blockCount", result.blocks().size());
		response.addProperty("placed", result.placed());
		Http.sendJson(exchange, 200, gson.toJson(response));
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
