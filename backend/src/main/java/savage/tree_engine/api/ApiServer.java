package savage.tree_engine.api;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import savage.tree_engine.BackendConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The HTTP front door. Owns the listener, the worker pool, authentication and
 * the single place where exceptions become responses.
 *
 * Routes register themselves through {@link #route}, so adding an endpoint
 * does not mean editing a nested if/else chain over split path segments -
 * the thing that made the old TreeApiHandler hard to extend.
 */
public final class ApiServer {
	public static final Logger LOGGER = LoggerFactory.getLogger("tree-engine-backend");

	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final BackendConfig config;
	private final HttpServer server;
	private final ExecutorService workers;
	private final byte[] expectedToken;

	private ApiServer(BackendConfig config, HttpServer server, ExecutorService workers) {
		this.config = config;
		this.server = server;
		this.workers = workers;
		this.expectedToken = ("Bearer " + config.token()).getBytes(StandardCharsets.UTF_8);
	}

	public static ApiServer start(BackendConfig config) throws IOException {
		// Loopback only. This service is reachable by the desktop app on the
		// same machine and by nothing else; binding a wildcard address would
		// expose arbitrary generation work to the network.
		HttpServer http = HttpServer.create(
			new InetSocketAddress("127.0.0.1", config.port()), 0);

		AtomicInteger counter = new AtomicInteger();
		ThreadFactory factory = r -> {
			Thread t = new Thread(r, "tree-engine-api-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		};
		ExecutorService workers = Executors.newFixedThreadPool(config.workerThreads(), factory);
		http.setExecutor(workers);

		return new ApiServer(config, http, workers);
	}

	public Gson gson() {
		return GSON;
	}

	/** Registers a route. Handlers may throw; failures become JSON errors. */
	public ApiServer route(String path, Route handler) {
		server.createContext(path, wrap(handler));
		return this;
	}

	public void listen() {
		server.start();
		LOGGER.info("Tree Engine backend listening on http://127.0.0.1:{}", config.port());
	}

	public void stop() {
		server.stop(0);
		workers.shutdown();
		try {
			if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
				workers.shutdownNow();
			}
		} catch (InterruptedException e) {
			workers.shutdownNow();
			Thread.currentThread().interrupt();
		}
		LOGGER.info("Tree Engine backend stopped");
	}

	private HttpHandler wrap(Route handler) {
		return exchange -> {
			try (exchange) {
				applyCors(exchange);

				// Preflight must be answered before the auth check: browsers
				// do not send Authorization on an OPTIONS preflight, so
				// requiring it here would reject the probe and the real
				// request would never be sent. The request that follows still
				// carries the token and is still checked.
				if ("OPTIONS".equals(exchange.getRequestMethod())) {
					exchange.sendResponseHeaders(204, -1);
					return;
				}

				if (!authorized(exchange)) {
					sendError(exchange, 401, "Unauthorized", null);
					return;
				}
				try {
					handler.handle(exchange);
				} catch (ApiException e) {
					if (e.status() >= 500) {
						LOGGER.error("{} {} -> {}", exchange.getRequestMethod(),
							exchange.getRequestURI().getPath(), e.status(), e);
					}
					sendError(exchange, e.status(), e.getMessage(), e.detail());
				} catch (Exception e) {
					LOGGER.error("Unhandled error on {} {}", exchange.getRequestMethod(),
						exchange.getRequestURI().getPath(), e);
					sendError(exchange, 500, "Internal server error", e.getMessage());
				}
			}
		};
	}

	/**
	 * The desktop app's webview runs on its own origin, so every call it makes
	 * here is cross-origin and needs these headers or the browser discards the
	 * response before the app ever sees it - surfacing as an opaque "failed to
	 * fetch" rather than an HTTP status.
	 *
	 * A wildcard origin is safe for this service specifically: it binds
	 * loopback only, and it authenticates with a bearer token rather than
	 * cookies, so a page that lacks the token cannot do anything with the
	 * permission to ask.
	 */
	private static void applyCors(HttpExchange exchange) {
		var headers = exchange.getResponseHeaders();
		headers.set("Access-Control-Allow-Origin", "*");
		headers.set("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS");
		headers.set("Access-Control-Allow-Headers", "Authorization, Content-Type");
		headers.set("Access-Control-Max-Age", "86400");
	}

	/**
	 * Constant-time bearer check. A blank configured token means the launcher
	 * never provisioned one, and every request is refused rather than the
	 * API being left open.
	 */
	private boolean authorized(HttpExchange exchange) {
		if (!config.hasToken()) {
			return false;
		}
		String header = exchange.getRequestHeaders().getFirst("Authorization");
		if (header == null) {
			return false;
		}
		return MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8), expectedToken);
	}

	private static void sendError(HttpExchange exchange, int status, String message, String detail) {
		JsonObject body = new JsonObject();
		body.addProperty("error", message);
		if (detail != null && !detail.isBlank()) {
			body.addProperty("detail", detail);
		}
		try {
			Http.sendJson(exchange, status, GSON.toJson(body));
		} catch (IOException io) {
			LOGGER.warn("Failed to write error response", io);
		}
	}

	/** A route handler that is allowed to fail. */
	@FunctionalInterface
	public interface Route {
		void handle(HttpExchange exchange) throws Exception;
	}
}
