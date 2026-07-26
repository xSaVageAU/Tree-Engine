package savage.tree_engine.datapack;

import net.minecraft.resources.Identifier;
import savage.tree_engine.api.ApiException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A datapack as it arrives over HTTP: a map of datapack-relative file path to
 * file contents, e.g.
 *
 * <pre>
 * "data/tree_engine/worldgen/configured_feature/my_oak.json" -> "{ ... }"
 * </pre>
 *
 * Paths are used rather than pre-split namespace/path pairs because that is
 * the shape the desktop app already has on disk, so the client does not have
 * to understand how Minecraft addresses resources.
 */
public record DatapackPayload(Map<String, String> files) {
	/** Guards against a client sending an unbounded number of tiny files. */
	private static final int MAX_FILES = 10_000;

	/**
	 * Converts the payload into resource ids the registry loader understands.
	 * Anything outside {@code data/<namespace>/...} is rejected loudly rather
	 * than silently ignored - a datapack that half-loads is worse than one
	 * that fails.
	 */
	public InMemoryPack toPack(String packId) {
		// An empty datapack is a normal state, not an error: a project with no
		// trees saved yet has nothing under data/. Rejecting it used to break
		// previewing entirely until the user saved any file at all.
		if (files == null) {
			return new InMemoryPack(packId, Map.of());
		}
		if (files.size() > MAX_FILES) {
			throw ApiException.badRequest(
				"Datapack contains too many files (" + files.size() + " > " + MAX_FILES + ")");
		}

		Map<Identifier, byte[]> entries = new LinkedHashMap<>();
		for (Map.Entry<String, String> entry : files.entrySet()) {
			Identifier id = toIdentifier(entry.getKey());
			String content = entry.getValue();
			if (content == null) {
				throw ApiException.badRequest("File has null content: " + entry.getKey());
			}
			entries.put(id, content.getBytes(StandardCharsets.UTF_8));
		}
		return new InMemoryPack(packId, entries);
	}

	private static Identifier toIdentifier(String rawPath) {
		String path = rawPath.replace('\\', '/');
		while (path.startsWith("/")) {
			path = path.substring(1);
		}
		if (path.contains("..")) {
			throw ApiException.badRequest("Illegal path segment in: " + rawPath);
		}
		if (!path.startsWith("data/")) {
			throw ApiException.badRequest(
				"Datapack file must live under data/: " + rawPath);
		}

		String remainder = path.substring("data/".length());
		int slash = remainder.indexOf('/');
		if (slash <= 0 || slash == remainder.length() - 1) {
			throw ApiException.badRequest(
				"Datapack file must be data/<namespace>/<path>: " + rawPath);
		}

		String namespace = remainder.substring(0, slash);
		String resourcePath = remainder.substring(slash + 1);
		try {
			return Identifier.fromNamespaceAndPath(namespace, resourcePath);
		} catch (Exception e) {
			throw ApiException.badRequest(
				"Not a valid resource path: " + rawPath, e.getMessage());
		}
	}

	/**
	 * A stable fingerprint of the payload's contents. Identical datapacks
	 * produce the same id, so re-sending an unchanged pack reuses the
	 * already-compiled registry instead of rebuilding it.
	 */
	public String fingerprint() {
		if (files == null) {
			return new DatapackPayload(Map.of()).fingerprint();
		}
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			List<String> keys = new ArrayList<>(files.keySet());
			Collections.sort(keys);
			for (String key : keys) {
				digest.update(key.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
				String value = files.get(key);
				digest.update(value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) 0);
			}
			return HexFormat.of().formatHex(digest.digest()).substring(0, 32);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}
}
