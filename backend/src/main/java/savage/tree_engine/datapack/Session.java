package savage.tree_engine.datapack;

import net.minecraft.core.RegistryAccess;

/**
 * A compiled datapack, held only in memory.
 *
 * Previews reference a session by id so the editor does not have to re-upload
 * the whole datapack on every keystroke. Nothing here is persisted - when the
 * session is evicted the registries become garbage.
 */
public record Session(
	String id,
	RegistryAccess.Frozen registries,
	int fileCount) {
}
