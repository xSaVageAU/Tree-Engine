package savage.tree_engine.registry;

import net.minecraft.resource.DirectoryResourcePack;
import net.minecraft.resource.ResourcePack;
import net.minecraft.resource.ResourcePackInfo;
import net.minecraft.resource.ResourcePackProfile;
import net.minecraft.resource.ResourcePackProvider;
import net.minecraft.resource.ResourceType;
import net.minecraft.resource.ResourcePackSource;
import net.minecraft.resource.ResourcePackPosition;
import net.minecraft.text.Text;
import savage.tree_engine.TreeEngine;
import savage.tree_engine.config.MainConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.Optional;

public class TreeEngineResourcePackProvider implements ResourcePackProvider {
    private final Path datapacksDir;

    public TreeEngineResourcePackProvider() {
        this.datapacksDir = MainConfig.getConfigDir().resolve("datapacks");
    }

    @Override
    public void register(Consumer<ResourcePackProfile> profileAdder) {
        // When the desktop launcher has an external project folder open, that
        // folder becomes the sole tree_engine: authoring pack - the local
        // default is neither auto-created nor registered alongside it, to
        // avoid two sources both defining the same tree_engine:<id> features.
        String activeProjectDir = MainConfig.get().active_project_dir;
        boolean hasExternalProject = activeProjectDir != null && !activeProjectDir.isBlank();

        TreeEngine.LOGGER.info("TreeEngineResourcePackProvider: Scanning for datapacks in " + datapacksDir);

        if (!Files.exists(datapacksDir)) {
            try {
                Files.createDirectories(datapacksDir);
                TreeEngine.LOGGER.info("Created datapacks directory: " + datapacksDir);

                if (!hasExternalProject) {
                    createDefaultDatapack();
                }
            } catch (IOException e) {
                TreeEngine.LOGGER.error("Failed to create datapacks directory: " + datapacksDir, e);
                return;
            }
        } else if (!hasExternalProject) {
            // Check if directory is empty
            try (Stream<Path> entries = Files.list(datapacksDir)) {
                if (entries.findAny().isEmpty()) {
                    TreeEngine.LOGGER.info("Datapacks directory is empty, creating default datapack");
                    createDefaultDatapack();
                }
            } catch (IOException e) {
                TreeEngine.LOGGER.error("Failed to check datapacks directory: " + datapacksDir, e);
            }
        }

        if (hasExternalProject) {
            TreeEngine.LOGGER.info("Registering active project as the tree_engine authoring pack: " + activeProjectDir);
            registerIfValidDatapack(Paths.get(activeProjectDir), profileAdder);
        }

        try (Stream<Path> paths = Files.list(datapacksDir)) {
            paths.filter(Files::isDirectory).forEach(path -> {
                // The local default authoring pack is superseded by the
                // external project above - don't register both.
                if (hasExternalProject && path.getFileName().toString().equals("tree_engine_trees")) {
                    TreeEngine.LOGGER.info("Skipping local default datapack - superseded by active project");
                    return;
                }
                registerIfValidDatapack(path, profileAdder);
            });
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to list datapacks in " + datapacksDir, e);
        }
    }

    /**
     * Validates that `path` looks like a datapack (has a data/ subfolder),
     * ensures it has a pack.mcmeta, and registers it as a top-priority
     * server-data resource pack. Used both for the local datapacks/*
     * subfolders and for an externally-chosen project folder.
     */
    private void registerIfValidDatapack(Path path, Consumer<ResourcePackProfile> profileAdder) {
        TreeEngine.LOGGER.info("Found potential datapack directory: " + path);

        // Skip if this IS the data folder itself (not a valid datapack root)
        if (path.getFileName() != null && path.getFileName().toString().equals("data")) {
            TreeEngine.LOGGER.info("Skipping 'data' folder - not a valid datapack root");
            return;
        }

        // Validate datapack structure - must have a data subfolder
        Path dataFolder = path.resolve("data");
        if (!Files.exists(dataFolder) || !Files.isDirectory(dataFolder)) {
            TreeEngine.LOGGER.warn("Skipping " + path + " - missing 'data' subfolder (not a valid datapack)");
            return;
        }

        TreeEngine.LOGGER.info("Valid datapack structure found at: " + path);

        // Ensure pack.mcmeta exists
        Path mcmeta = path.resolve("pack.mcmeta");
        if (!Files.exists(mcmeta)) {
            try {
                String content = "{\n" +
                        "  \"pack\": {\n" +
                        "    \"pack_format\": 88,\n" +
                        "    \"supported_formats\": {\n" +
                        "      \"min_inclusive\": 88,\n" +
                        "      \"max_inclusive\": 88\n" +
                        "    },\n" +
                        "    \"description\": \"Tree Engine Datapack\"\n" +
                        "  }\n" +
                        "}";
                Files.writeString(mcmeta, content);
                TreeEngine.LOGGER.info("Generated pack.mcmeta for " + path);
            } catch (IOException e) {
                TreeEngine.LOGGER.error("Failed to create pack.mcmeta for " + path, e);
                return;
            }
        } else {
            TreeEngine.LOGGER.info("pack.mcmeta already exists at: " + mcmeta);
        }

        String id = "tree_engine/" + path.getFileName().toString();
        Text title = Text.literal("Tree Engine: " + path.getFileName());
        ResourcePackSource source = ResourcePackSource.create((name) -> Text.literal("Tree Engine"), true);

        ResourcePackInfo info = new ResourcePackInfo(id, title, source, Optional.empty());

        ResourcePackProfile profile = ResourcePackProfile.create(
            info,
            new ResourcePackProfile.PackFactory() {
                @Override
                public ResourcePack open(ResourcePackInfo info) {
                    return new DirectoryResourcePack(info, path);
                }

                @Override
                public ResourcePack openWithOverlays(ResourcePackInfo info, ResourcePackProfile.Metadata metadata) {
                    return new DirectoryResourcePack(info, path);
                }
            },
            ResourceType.SERVER_DATA,
            new ResourcePackPosition(true, ResourcePackProfile.InsertionPosition.TOP, false)
        );

        if (profile != null) {
            profileAdder.accept(profile);
            TreeEngine.LOGGER.info("Registered datapack: " + id);
        } else {
            TreeEngine.LOGGER.warn("Failed to create profile for datapack: " + id + " (profile was null)");
        }
    }

    private void createDefaultDatapack() {
        try {
            Path defaultPack = datapacksDir.resolve("tree_engine_trees");
            Path dataDir = defaultPack.resolve("data").resolve("tree_engine").resolve("worldgen").resolve("configured_feature");
            Files.createDirectories(dataDir);
            
            // Create pack.mcmeta
            Path mcmeta = defaultPack.resolve("pack.mcmeta");
            String content = "{\n" +
                    "  \"pack\": {\n" +
                    "    \"pack_format\": 88,\n" +
                    "    \"supported_formats\": {\n" +
                    "      \"min_inclusive\": 88,\n" +
                      "      \"max_inclusive\": 88\n" +
                    "    },\n" +
                    "    \"description\": \"Tree Engine Custom Trees\"\n" +
                    "  }\n" +
                    "}";
            Files.writeString(mcmeta, content);
            
            TreeEngine.LOGGER.info("Created default datapack at: " + defaultPack);
            TreeEngine.LOGGER.info("Place your tree JSON files in: " + dataDir);
        } catch (IOException e) {
            TreeEngine.LOGGER.error("Failed to create default datapack", e);
        }
    }
}
