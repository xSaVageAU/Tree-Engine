package savage.tree_engine;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The backend's entire configuration: where to listen and what token to
 * accept. Read once at startup and never written back - the desktop launcher
 * owns this file and the backend has no business editing it.
 *
 * The old mod hand-assembled its config JSON as a string so it could embed
 * comments, which meant every added field touched a serializer. This is a
 * plain record through Gson instead.
 */
public record BackendConfig(int port, String token, int workerThreads, int sessionLimit) {
	public static final Path CONFIG_FILE =
		Path.of("config", "tree-engine-backend.json");

	private static final int DEFAULT_PORT = 3000;
	private static final int DEFAULT_WORKERS = 4;
	private static final int DEFAULT_SESSION_LIMIT = 8;

	/**
	 * Loads config, falling back to defaults when the file is absent so the
	 * backend still boots (and logs loudly) if launched without the app.
	 * An absent or blank token disables the service outright rather than
	 * silently exposing an unauthenticated API.
	 */
	public static BackendConfig load(Gson gson) throws IOException {
		if (!Files.exists(CONFIG_FILE)) {
			return new BackendConfig(DEFAULT_PORT, "", DEFAULT_WORKERS, DEFAULT_SESSION_LIMIT);
		}
		try {
			BackendConfig parsed =
				gson.fromJson(Files.readString(CONFIG_FILE), BackendConfig.class);
			if (parsed == null) {
				throw new IOException("config file is empty: " + CONFIG_FILE);
			}
			return parsed.withDefaults();
		} catch (JsonSyntaxException e) {
			throw new IOException("config file is not valid JSON: " + CONFIG_FILE, e);
		}
	}

	private BackendConfig withDefaults() {
		return new BackendConfig(
			port > 0 ? port : DEFAULT_PORT,
			token == null ? "" : token,
			workerThreads > 0 ? Math.min(workerThreads, 16) : DEFAULT_WORKERS,
			sessionLimit > 0 ? sessionLimit : DEFAULT_SESSION_LIMIT);
	}

	public boolean hasToken() {
		return token != null && !token.isBlank();
	}
}
