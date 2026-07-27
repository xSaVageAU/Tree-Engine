package savage.tree_engine;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import savage.tree_engine.api.ApiServer;
import savage.tree_engine.api.Http;
import savage.tree_engine.datapack.SessionCache;
import savage.tree_engine.datapack.SessionRoutes;
import savage.tree_engine.preview.chunk.ChunkPreviewRoutes;
import savage.tree_engine.registry.RegistryRoutes;
import savage.tree_engine.preview.tree.BenchmarkRoutes;
import savage.tree_engine.preview.tree.TreePreviewRoutes;

import java.io.IOException;

/**
 * Entry point for the Tree Engine backend.
 *
 * This mod is a generation service, not a game feature. It has no commands,
 * no mixins, no registry mutation and no in-game presence - it boots a
 * dedicated server solely to obtain a fully bootstrapped registry, then
 * answers HTTP requests against it. Nothing it does is persisted.
 */
public class TreeEngineBackend implements ModInitializer {
	public static final String MOD_ID = "tree-engine-backend";

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private ApiServer api;
	private SessionCache sessions;

	@Override
	public void onInitialize() {
		// The API only starts once the server is fully up: every route needs
		// a loaded registry, so exposing the port earlier would just mean
		// serving errors during boot.
		ServerLifecycleEvents.SERVER_STARTED.register(this::startApi);
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopApi());
	}

	private void startApi(MinecraftServer server) {
		BackendConfig config;
		try {
			config = BackendConfig.load(GSON);
		} catch (IOException e) {
			ApiServer.LOGGER.error("Could not read {} - backend will not start",
				BackendConfig.CONFIG_FILE, e);
			return;
		}

		if (!config.hasToken()) {
			ApiServer.LOGGER.error(
				"No auth token configured in {} - refusing to start an unauthenticated API. "
					+ "This file is written by the Tree Engine desktop app.",
				BackendConfig.CONFIG_FILE);
			return;
		}

		try {
			api = ApiServer.start(config);
			sessions = new SessionCache(config.sessionLimit());
			registerRoutes(api, server, config);
			api.listen();
		} catch (IOException e) {
			ApiServer.LOGGER.error("Failed to bind port {}", config.port(), e);
		}
	}

	private void registerRoutes(ApiServer api, MinecraftServer server, BackendConfig config) {
		api.route("/v1/health", exchange -> {
			Http.require(exchange, "GET");
			JsonObject body = new JsonObject();
			body.addProperty("status", "ok");
			body.addProperty("minecraftVersion", server.getServerVersion());
			body.addProperty("backendVersion", modVersion());
			body.addProperty("sessions", sessions.size());
			Http.sendJson(exchange, 200, GSON.toJson(body));
		});

		new SessionRoutes(server, sessions, GSON).register(api);
		new TreePreviewRoutes(server, sessions, GSON).register(api);
		new ChunkPreviewRoutes(server, sessions, GSON, config.colormapsDir()).register(api);
		new RegistryRoutes(server, sessions, GSON).register(api);
		new BenchmarkRoutes(server, sessions, GSON).register(api);
	}

	private static String modVersion() {
		return FabricLoader.getInstance().getModContainer(MOD_ID)
			.map(c -> c.getMetadata().getVersion().getFriendlyString())
			.orElse("unknown");
	}

	private void stopApi() {
		if (api != null) {
			api.stop();
			api = null;
		}
		if (sessions != null) {
			// Compiled registry sets are large; drop them promptly rather
			// than waiting for the process to exit.
			sessions.clear();
			sessions = null;
		}
	}
}
