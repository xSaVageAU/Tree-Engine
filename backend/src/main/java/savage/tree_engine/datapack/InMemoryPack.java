package savage.tree_engine.datapack;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A server-data pack backed entirely by an in-memory map of
 * resource id -> file bytes.
 *
 * This is what lets the backend accept a datapack in an HTTP request and hand
 * it to Minecraft's registry loader without ever touching disk. Keys are
 * addressed the way the game addresses datapack files: a namespace plus a
 * path like {@code worldgen/configured_feature/my_tree.json}.
 */
public final class InMemoryPack implements PackResources {
	private final PackLocationInfo location;
	private final Map<Identifier, byte[]> entries;

	public InMemoryPack(String id, Map<Identifier, byte[]> entries) {
		this.location = new PackLocationInfo(
			id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
		this.entries = Map.copyOf(entries);
	}

	public int size() {
		return entries.size();
	}

	@Override
	public IoSupplier<InputStream> getRootResource(String... path) {
		// No pack.mcmeta: the registry loader reads resources directly and
		// never consults pack metadata for a pack handed to it in code.
		return null;
	}

	@Override
	public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
		if (type != PackType.SERVER_DATA) return null;
		byte[] data = entries.get(id);
		if (data == null) return null;
		return () -> new ByteArrayInputStream(data);
	}

	@Override
	public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
		if (type != PackType.SERVER_DATA) return;
		for (Map.Entry<Identifier, byte[]> e : entries.entrySet()) {
			Identifier id = e.getKey();
			if (!id.getNamespace().equals(namespace)) continue;
			if (!id.getPath().startsWith(prefix)) continue;
			byte[] data = e.getValue();
			output.accept(id, () -> new ByteArrayInputStream(data));
		}
	}

	@Override
	public Set<String> getNamespaces(PackType type) {
		if (type != PackType.SERVER_DATA) return Set.of();
		Set<String> namespaces = new HashSet<>();
		for (Identifier id : entries.keySet()) {
			namespaces.add(id.getNamespace());
		}
		return namespaces;
	}

	@Override
	public <T> T getMetadataSection(MetadataSectionType<T> type) {
		return null;
	}

	@Override
	public PackLocationInfo location() {
		return location;
	}

	@Override
	public void close() {
		// Nothing to release - the backing map is garbage collected with the pack.
	}
}
