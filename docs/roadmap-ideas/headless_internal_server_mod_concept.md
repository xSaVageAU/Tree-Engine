## Technical Concept Document: Headless Internal Servers for Dynamic Datapack World Generation

### Overview
This document outlines the concept of running isolated headless internal servers within a Fabric mod, allowing dynamic loading of datapacks and execution of world generation logic independently of the main internal server. This is particularly useful for applications like tree editors or other worldgen preview tools.

---

### Goals
1. Allow multiple headless servers to run in memory simultaneously.
2. Load custom datapacks dynamically into headless servers without affecting the main server.
3. Execute world generation logic in these headless servers.
4. Extract generated world data for use in external tools (e.g., 3D previews in a web browser).

---

### Architecture

```
Client Instance
 ├─ Main Internal Server (player world)
 │    └─ Fully playable, unaffected by headless servers
 └─ Headless Servers (worldgen engines)
      ├─ Server #1: Custom Datapack A
      ├─ Server #2: Custom Datapack B
      └─ Server #3: Custom Datapack C
```

#### Key Components
- **Main Server**: Regular internal server used by the client.
- **Headless Server**: `MinecraftServer` instances running without players or GUI, used for world generation.
- **Datapack Management**: Each headless server maintains its own `ResourceManager` and `RegistryAccess`.
- **Tick Loop**: Headless servers can be ticked manually or in separate threads.

---

### Workflow

1. **Initialization**
   - Create a headless server instance in memory using Fabric APIs.
   - Configure world settings and resource management.

2. **Dynamic Datapack Loading**
   - User drags a datapack into the editor.
   - Mod creates or selects a headless server instance.
   - Load the datapack into the headless server's `ResourceManager`.
   - Call `MinecraftServer.reloadResources(List<ResourcePackProfile>)` to apply the datapack.

3. **World Generation Execution**
   - Access `ServerLevel` objects from the headless server.
   - Invoke world generation code (trees, biomes, structures, etc.).
   - Ticking can be manual or scheduled in a separate thread.

4. **Data Extraction**
   - Extract generated structures, chunks, or features.
   - Convert to JSON, NBT, or other formats for external preview tools.

5. **Lifecycle Management**
   - Optionally discard headless servers after use to free memory.
   - Reuse headless servers for repeated generation tasks.

---

### Advantages
- **Isolation**: Main server remains unaffected by datapacks loaded in headless servers.
- **Dynamic Reloading**: New datapacks can be tested immediately without restarting any servers.
- **Parallel Generation**: Multiple headless servers can run simultaneously for concurrent worldgen tasks.
- **Lightweight**: No GUI or player required.
- **Fabric Compatibility**: Fully integrates with Fabric API and modding ecosystem.

---

### Considerations
- **Memory Usage**: Multiple servers in memory can be heavy; monitor resource usage.
- **Thread Safety**: Ticking headless servers in parallel requires careful threading.
- **Worldgen Dependencies**: Some features may require dummy player entities for generation.
- **Registry Management**: Ensure each headless server has separate registries to avoid conflicts.

---

### Conclusion
By implementing headless internal servers, a Fabric mod can support dynamic, isolated world generation with custom datapacks. This approach is ideal for tools like tree editors, allowing users to preview generation logic in real-time without interfering with the main game server.

---

### References
- Minecraft Server/IntegratedServer classes
- Fabric API documentation
- `MinecraftServer.reloadResources()` for datapack management
- `ServerLevel` and worldgen classes for executing generation logic

