package savage.tree_engine.datapack;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.MinecraftServer;
import savage.tree_engine.api.ApiException;
import savage.tree_engine.api.ApiServer;
import savage.tree_engine.api.Http;

import java.util.concurrent.Executor;

/**
 * Session lifecycle: hand the backend a datapack, get back an id to reference
 * it by. Both preview modes take a sessionId rather than a datapack, so an
 * editor that is re-rendering on every keystroke uploads the pack once.
 */
public final class SessionRoutes {
	private static final String PREFIX = "/v1/session";

	private final MinecraftServer server;
	private final SessionCache cache;
	private final Gson gson;

	public SessionRoutes(MinecraftServer server, SessionCache cache, Gson gson) {
		this.server = server;
		this.cache = cache;
		this.gson = gson;
	}

	public void register(ApiServer api) {
		api.route(PREFIX, this::handle);
	}

	private void handle(HttpExchange exchange) throws Exception {
		String path = exchange.getRequestURI().getPath();
		boolean hasId = path.length() > PREFIX.length() + 1;

		switch (exchange.getRequestMethod()) {
			case "POST" -> {
				if (hasId) throw ApiException.methodNotAllowed();
				create(exchange);
			}
			case "DELETE" -> {
				if (!hasId) throw ApiException.badRequest("Missing session id");
				delete(exchange);
			}
			default -> throw ApiException.methodNotAllowed();
		}
	}

	private void create(HttpExchange exchange) throws Exception {
		DatapackPayload payload = Http.readJson(exchange, gson, DatapackPayload.class);
		String id = payload.fingerprint();

		// An unchanged datapack fingerprints identically, so a re-upload is a
		// cache hit rather than a recompile.
		Session existing = cache.get(id);
		if (existing != null) {
			respond(exchange, existing, true);
			return;
		}

		InMemoryPack pack = payload.toPack("tree-engine-session-" + id);

		// Compilation is CPU-bound and can take a moment on a large pack; it
		// runs on the calling API worker, which is already off the server
		// thread, so the game loop is never blocked.
		Executor inline = Runnable::run;
		RegistryAccess.Frozen registries;
		try {
			registries = RegistrySet.compile(server, pack, inline);
		} catch (ApiException e) {
			// The loader names the file that failed but not why. Re-parse it
			// here to recover the codec's actual complaint.
			String precise = DatapackDiagnostics.explain(
				server.registryAccess(), payload, e.detail());
			if (precise != null) {
				throw new ApiException(e.status(), e.getMessage(), precise, e);
			}
			throw e;
		}

		Session session = new Session(id, registries, pack.size());
		cache.put(session);
		ApiServer.LOGGER.info("Compiled datapack session {} ({} files, {} cached)",
			id, pack.size(), cache.size());

		respond(exchange, session, false);
	}

	private void delete(HttpExchange exchange) throws Exception {
		String id = Http.tail(exchange, PREFIX + "/");
		boolean removed = cache.remove(id);
		JsonObject body = new JsonObject();
		body.addProperty("removed", removed);
		Http.sendJson(exchange, removed ? 200 : 404, gson.toJson(body));
	}

	private void respond(HttpExchange exchange, Session session, boolean cached) throws Exception {
		JsonObject body = new JsonObject();
		body.addProperty("sessionId", session.id());
		body.addProperty("fileCount", session.fileCount());
		body.addProperty("cached", cached);
		Http.sendJson(exchange, 200, gson.toJson(body));
	}
}
