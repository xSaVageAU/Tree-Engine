Dynamic Datapack Registration Implementation Plan
Goal
Register tree JSON files as real Minecraft datapacks so they can be used with /place feature tree_engine:<name> and generate 1:1 accurately in both preview and in-game.

Current Issues
❌ Custom /tree_engine place command that doesn't use Minecraft's feature system
❌ Web editor sends only tree config, not full feature JSON
❌ Backend generates with Feature.TREE only, ignoring wrappers like random_patch
❌ No dynamic registry registration for features
Proposed Changes
1. Dynamic Feature Registration
File: src/main/java/savage/tree_engine/registry/FeatureRegistry.java [NEW]

Load all JSON files from config/tree_engine/datapacks/your_pack/data/tree_engine/worldgen/configured_feature/
Parse each using ConfiguredFeature.CODEC with RegistryOps
Register into Minecraft's RegistryKeys.CONFIGURED_FEATURE registry
Use Identifier like tree_engine:live_oak
Register on SERVER_STARTING event (before world load)
2. Remove Custom Place Command
File: src/main/java/savage/tree_engine/command/TreeEngineCommand.java

Remove placeTree() method and command registration
Keep only /tree_engine reload command
Users will use vanilla /place feature tree_engine:<name>
3. Update Web Editor - Frontend
File: src/main/resources/web/main.js

Change generateTree() to send full feature JSON (not just config)
Include the type field and all wrappers
Send the complete JSON structure from the file
4. Update Web Editor - Backend
File: src/main/java/savage/tree_engine/web/WebEditorServer.java

Update GenerateHandler to parse full ConfiguredFeature (not just TreeFeatureConfig)
Use RegistryOps for parsing
Call configuredFeature.generate() instead of Feature.TREE.generate()
This will handle random_patch, selectors, everything
5. Update Tree API Handler
File: src/main/java/savage/tree_engine/web/TreeApiHandler.java

Update to serve full feature JSON (not just config)
Include type field in responses
Verification Plan
Test Cases
Simple tree (minecraft:tree) - Should work as before
Random selector (simple_random_selector → tree) - Should pick first tree
Random patch (random_patch tries:80 → tree) - Should generate 80 trees in preview AND in-game
Nested wrappers (selector → patch → selector → tree) - Should work fully
Commands to Test
/place feature tree_engine:live_oak
/place feature tree_engine:forest_pine
Expected Results
Preview shows same structure as in-game (80 trees for live_oak)
/place command works with vanilla syntax
All valid datapack JSON works 1:1
Implementation Order
Create FeatureRegistry.java with dynamic registration
Remove /tree_engine place command
Update backend GenerateHandler to use full ConfiguredFeature
Update frontend to send full JSON
Test with simple trees first, then complex ones
Update TreeApiHandler to serve full JSON
Breaking Changes
⚠️ /tree_engine place <name> command removed
✅ Use /place feature tree_engine:<name> instead