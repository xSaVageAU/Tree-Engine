# Hot Reloading Minecraft Datapacks: Reflection-Based Approach

## The Problem

Minecraft's `/reload` command **does not reload world generation features**. This includes:
- Configured Features (tree definitions, ore generation, etc.)
- Placed Features (where and how features spawn)
- Biome definitions
- Structure definitions

These are loaded once when the world/server starts and cached in immutable registries. Changes to datapack JSON files only take effect after a full world/server restart.

## Why This Matters for Tree Engine

Your Tree Engine mod allows users to create and edit custom trees through a web interface. When users:
- Create new tree configurations
- Modify existing tree parameters (height, blocks, etc.)
- Update tree replacers (which vanilla trees use which custom trees)

They expect to see changes immediately in-game, but currently must restart the server.

## The Solution: Reflection-Based Hot Reloading

### How Savs Better Trees Does It

The Savs Better Trees mod demonstrates the correct approach:

1. **Datapacks Load Normally**: JSON files are parsed and loaded into registries at server start
2. **Runtime Modification**: When config changes, reflection modifies the already-loaded `TreeFeatureConfig` objects in memory
3. **Immediate Effect**: New tree generations use the modified configurations without restart

```java
// From SavsWorldGen.modifyOak3Feature()
private static void modifyOak3Feature(Registry<ConfiguredFeature<?, ?>> registry, String featureId, Block logBlock, Block foliageBlock) {
    // Get the loaded feature from registry
    RegistryEntry.Reference<ConfiguredFeature<?, ?>> entry = registry.getEntry(id).orElse(null);
    ConfiguredFeature<?, ?> feature = entry.value();
    TreeFeatureConfig treeConfig = (TreeFeatureConfig) feature.config();

    // Use reflection to modify the final fields directly in memory
    var trunkField = TreeFeatureConfig.class.getDeclaredField("trunkProvider");
    trunkField.setAccessible(true);
    trunkField.set(treeConfig, SimpleBlockStateProvider.of(logBlock));
}
```

### Why Reflection Works

- **Direct Memory Modification**: Changes the actual objects used by world generation
- **No Registry Recreation**: Avoids the complexity of rebuilding entire registries
- **Immediate Effect**: New chunks generate with updated configurations
- **Thread Safe**: Happens during server startup or command execution

## Applying This to Tree Engine

### Architecture Overview

Tree Engine needs a hybrid approach:

1. **Datapacks for Persistence**: JSON files saved to disk for backup/restart
2. **Direct Registry Injection**: Use reflection to register/update features immediately
3. **Tree Replacer Updates**: Modify existing replacer features to reference new trees

### Implementation Strategy

#### 1. Track Custom Features Registry

```java
// In TreeEngine.java
public static Map<String, ConfiguredFeature<?, ?>> customTrees = new ConcurrentHashMap<>();
public static Map<String, TreeReplacer> activeReplacers = new ConcurrentHashMap<>();
```

#### 2. Direct Feature Registration

When saving a tree, register it directly in the server's registry:

```java
// In TreeApiHandler.handleSave()
private void handleSave(HttpExchange exchange) throws IOException {
    // Parse JSON and create ConfiguredFeature
    RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE, minecraftServer.getRegistryManager());
    DataResult<ConfiguredFeature<?, ?>> result = ConfiguredFeature.CODEC.parse(ops, json);
    ConfiguredFeature<?, ?> feature = result.getOrThrow(s -> new RuntimeException("Parse failed: " + s));

    // Register directly in memory
    String featureId = "tree_engine:" + id;
    registerFeatureDirectly(featureId, feature);

    // Also save JSON file for persistence
    saveJsonFile(id, json);
}

private static void registerFeatureDirectly(String featureId, ConfiguredFeature<?, ?> feature) {
    try {
        var registryManager = minecraftServer.getRegistryManager();
        var registry = registryManager.getOrThrow(RegistryKeys.CONFIGURED_FEATURE);

        // Use reflection to access the registry's backing map
        var registryClass = registry.getClass();
        var backingMapField = registryClass.getDeclaredField("backingMap");
        backingMapField.setAccessible(true);

        @SuppressWarnings("unchecked")
        Map<Identifier, ConfiguredFeature<?, ?>> backingMap =
            (Map<Identifier, ConfiguredFeature<?, ?>>) backingMapField.get(registry);

        backingMap.put(Identifier.of(featureId), feature);

        // Track for our own reference
        TreeEngine.customTrees.put(featureId, feature);

        TreeEngine.LOGGER.info("Hot-reloaded feature: " + featureId);

    } catch (Exception e) {
        TreeEngine.LOGGER.error("Failed to register feature directly", e);
    }
}
```

#### 3. Tree Replacer Updates

When saving replacers, update the existing simple_random_selector features:

```java
// In TreeReplacerManager.saveReplacer()
public static void saveReplacer(TreeReplacer replacer) throws IOException {
    // Save JSON file
    generateDatapackFile(replacer);

    // Update in-memory registry
    updateReplacerInRegistry(replacer);
}

private static void updateReplacerInRegistry(TreeReplacer replacer) {
    String replacerId = "minecraft:" + replacer.vanilla_tree_id.split(":")[1];

    try {
        var registry = minecraftServer.getRegistryManager().getOrThrow(RegistryKeys.CONFIGURED_FEATURE);
        var entry = registry.getEntry(Identifier.of(replacerId));

        if (entry.isPresent()) {
            var feature = entry.get().value();

            // Create new random selector with updated pool
            var randomSelector = createRandomSelector(replacer.replacement_pool);

            // Replace the feature in registry using reflection
            // (Similar approach to registerFeatureDirectly)

            TreeEngine.activeReplacers.put(replacerId, replacer);
        }
    } catch (Exception e) {
        TreeEngine.LOGGER.error("Failed to update replacer in registry", e);
    }
}
```

### Command Integration

Add a reload command that refreshes all custom features:

```java
// In TreeEngineCommand.java
private static int reloadTrees(CommandContext<ServerCommandSource> context) {
    try {
        // Reload all custom trees from JSON files
        reloadAllCustomTrees();

        // Update all active replacers
        TreeReplacerManager.reloadAllReplacers();

        context.getSource().sendFeedback(
            () -> Text.literal("§aTree Engine: All trees hot-reloaded!"), true);
        return 1;
    } catch (Exception e) {
        context.getSource().sendError(
            Text.literal("§cTree Engine: Reload failed: " + e.getMessage()));
        return 0;
    }
}

private static void reloadAllCustomTrees() {
    Path treeDir = Paths.get("config", "tree_engine", "datapacks", "tree_engine_trees",
                           "data", "tree_engine", "worldgen", "configured_feature");

    try (Stream<Path> files = Files.list(treeDir)) {
        files.filter(p -> p.toString().endsWith(".json"))
             .forEach(file -> {
                 try {
                     String id = file.getFileName().toString().replace(".json", "");
                     String json = Files.readString(file);
                     JsonElement jsonElement = JsonParser.parseString(json);

                     // Parse and register
                     RegistryOps<JsonElement> ops = RegistryOps.of(JsonOps.INSTANCE,
                         minecraftServer.getRegistryManager());
                     DataResult<ConfiguredFeature<?, ?>> result =
                         ConfiguredFeature.CODEC.parse(ops, jsonElement);
                     ConfiguredFeature<?, ?> feature = result.getOrThrow();

                     registerFeatureDirectly("tree_engine:" + id, feature);

                 } catch (Exception e) {
                     TreeEngine.LOGGER.error("Failed to reload tree: " + file, e);
                 }
             });
    } catch (IOException e) {
        TreeEngine.LOGGER.error("Failed to scan tree directory", e);
    }
}
```

## Web Interface Integration

Update the web UI to trigger hot reloading after saves:

```javascript
// In tree-manager.js saveCurrentTree()
async saveCurrentTree() {
    // ... existing save logic ...

    if (response.ok) {
        // Trigger hot reload
        await fetch('/api/hot-reload', { method: 'POST' });

        alert('Tree saved and hot-reloaded!');
    }
}
```

Add the endpoint to TreeApiHandler:

```java
if ("POST".equals(method) && "hot-reload".equals(endpoint)) {
    TreeEngineCommand.reloadTrees(context);
    sendResponse(exchange, 200, "{\"status\": \"reloaded\"}");
}
```

## Benefits of This Approach

1. **Immediate Feedback**: Users see changes instantly
2. **No World Restart**: Changes apply to new chunk generation immediately
3. **Persistence**: JSON files still saved for server restarts
4. **Performance**: Only affects custom features, not entire datapack system
5. **Compatibility**: Works with existing Minecraft systems

## Technical Considerations

### Thread Safety
- Registry modifications should happen on the main server thread
- Use `CompletableFuture.supplyAsync()` to schedule on main thread if needed

### Error Handling
- Validate JSON before attempting registration
- Provide fallback to file-only saving if reflection fails
- Log detailed errors for debugging

### Memory Management
- Keep references to custom features to prevent garbage collection
- Clean up old features when replaced

### Version Compatibility
- Registry structure may change between Minecraft versions
- Test reflection field access on each major update

## Comparison: Datapack Reload vs Reflection

| Aspect | Datapack Reload (/reload) | Reflection Approach |
|--------|------------------------|-------------------|
| **Scope** | All datapacks | Targeted features only |
| **Performance** | Heavy (reloads everything) | Light (direct memory update) |
| **World Gen** | ❌ Doesn't work | ✅ Works |
| **Functions** | ✅ Works | N/A |
| **Loot Tables** | ✅ Works | N/A |
| **Recipes** | ✅ Works | N/A |
| **Complexity** | Simple | Complex (reflection) |
| **Reliability** | High | Medium (version dependent) |

## Conclusion

For Tree Engine's use case, the reflection-based approach is necessary because:
1. World generation features don't reload with `/reload`
2. Users expect immediate feedback when editing trees
3. The mod creates dynamic content that needs runtime updates

This gives you the hot reloading behavior users expect while maintaining compatibility with Minecraft's systems.