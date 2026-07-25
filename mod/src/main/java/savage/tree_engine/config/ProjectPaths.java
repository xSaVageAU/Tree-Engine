package savage.tree_engine.config;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Single source of truth for where the active project's datapack lives on
 * disk. Previously this path was hardcoded independently in five different
 * places (TreeApiHandler, TreeReplacerManager, TreeEngineCommand) - all
 * pointing at the same fixed folder. Centralizing it here is what lets the
 * desktop launcher point the mod at an arbitrary, user-chosen folder
 * (config's active_project_dir) instead.
 *
 * When active_project_dir is unset, this falls back to the historical
 * default folder rather than failing - the mod is a standalone Fabric mod
 * usable without the desktop launcher at all (see the in-game
 * /tree_engine reload command), and that use case must keep working
 * unchanged.
 */
public class ProjectPaths {
    private static final Path DEFAULT_ROOT =
        Paths.get("config", "tree_engine", "datapacks", "tree_engine_trees");

    /** The active project's datapack root - the folder containing pack.mcmeta and data/. */
    public static Path getProjectRoot() {
        String active = MainConfig.get().active_project_dir;
        if (active != null && !active.isBlank()) {
            return Paths.get(active);
        }
        return DEFAULT_ROOT;
    }

    /** data/{namespace}/worldgen/configured_feature under the active project. */
    public static Path getConfiguredFeatureDir(String namespace) {
        return getProjectRoot().resolve("data").resolve(namespace).resolve("worldgen").resolve("configured_feature");
    }

    /** data/tree_engine/worldgen/configured_feature - where the editor's own trees live. */
    public static Path getConfiguredFeatureDir() {
        return getConfiguredFeatureDir("tree_engine");
    }

    /** data/tree_engine/worldgen/placed_feature - placement rules for the editor's own trees. */
    public static Path getPlacedFeatureDir() {
        return getProjectRoot().resolve("data").resolve("tree_engine").resolve("worldgen").resolve("placed_feature");
    }
}
