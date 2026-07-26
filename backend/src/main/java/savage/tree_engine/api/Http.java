package savage.tree_engine.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Request/response plumbing shared by every route. Centralizing it here is
 * what stops each handler from re-implementing its own status codes, headers
 * and size limits - the failure mode of the old TreeApiHandler, where six
 * handlers each wrote slightly different error JSON.
 */
public final class Http {
	/**
	 * Cap on a request body. Datapack uploads are the large case; a
	 * legitimate one is far below this, and without a cap a single request
	 * could exhaust the heap.
	 */
	private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;

	private Http() {
	}

	public static String readBody(HttpExchange exchange) throws IOException {
		try (InputStream in = exchange.getRequestBody()) {
			byte[] bytes = in.readNBytes(MAX_BODY_BYTES + 1);
			if (bytes.length > MAX_BODY_BYTES) {
				throw ApiException.payloadTooLarge(
					"Request body exceeds " + (MAX_BODY_BYTES / (1024 * 1024)) + " MiB");
			}
			return new String(bytes, StandardCharsets.UTF_8);
		}
	}

	public static JsonElement readJson(HttpExchange exchange) throws IOException {
		String body = readBody(exchange);
		if (body.isBlank()) {
			throw ApiException.badRequest("Request body is empty");
		}
		try {
			return JsonParser.parseString(body);
		} catch (JsonSyntaxException e) {
			throw ApiException.badRequest("Request body is not valid JSON", e.getMessage());
		}
	}

	public static <T> T readJson(HttpExchange exchange, Gson gson, Class<T> type) throws IOException {
		try {
			T parsed = gson.fromJson(readBody(exchange), type);
			if (parsed == null) {
				throw ApiException.badRequest("Request body is empty");
			}
			return parsed;
		} catch (JsonSyntaxException e) {
			throw ApiException.badRequest("Request body is not valid JSON", e.getMessage());
		}
	}

	public static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	/** Rejects anything but the expected method, so handlers can assume it. */
	public static void require(HttpExchange exchange, String method) {
		if (!method.equals(exchange.getRequestMethod())) {
			throw ApiException.methodNotAllowed();
		}
	}

	/**
	 * The trailing path segment after {@code prefix}, URL-decoded. Used for
	 * ids that may legitimately contain slashes (namespaced feature ids like
	 * {@code wythers:biomes/forest/oak}), which the old handler's
	 * {@code path.split("/")} could not represent.
	 */
	public static String tail(HttpExchange exchange, String prefix) {
		String path = exchange.getRequestURI().getPath();
		if (!path.startsWith(prefix) || path.length() == prefix.length()) {
			throw ApiException.notFound("Missing id in path");
		}
		return java.net.URLDecoder.decode(
			path.substring(prefix.length()), StandardCharsets.UTF_8);
	}
}
