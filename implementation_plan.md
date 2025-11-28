# Tree Engine Hot Reloading Implementation Plan

## Overview

This plan outlines the implementation of hot reloading for Tree Engine's custom trees and tree replacers using reflection-based registry modification. The goal is to allow users to see changes immediately in-game without requiring a server restart, while maintaining datapack persistence for server restarts.

## Current State Analysis

### Existing Architecture
- **TreeEngine.java**: Main mod initializer, sets up commands, config, and web server
- **TreeApiHandler.java**: REST API for tree/replacer CRUD operations, currently only saves JSON files
- **TreeReplacerManager.java**: Manages replacer configurations via datapack file generation
- **TreeEngineCommand.java**: Web server management commands, no datapack reload functionality

### Current Limitations
- Changes require `/reload` command (which doesn't affect world generation features)
- Full server restart needed for world generation changes
- No immediate feedback for users editing trees in web interface

### Target Architecture
- Hybrid approach: JSON persistence + direct registry injection
- Reflection-based modification of immutable registries
- Immediate effect on new chunk generation
- Backward compatibility with existing datapack system

## Implementation Steps

### Phase 1: Core Infrastructure

#### 1.1 Add Registry Tracking Maps
**File**: `TreeEngine.java`
- Add static maps to track custom features and active replacers
- Initialize maps during mod initialization

```java
public static Map<String, ConfiguredFeature<?, ?>> customTrees = new ConcurrentHashMap<>();
public static Map<String, TreeReplacer> activeReplacers = new ConcurrentHashMap<>();
```

#### 1.2 Create Reflection Utilities
**File**: New `src/main/java/savage/tree_engine/util/RegistryUtils.java`
- Implement `registerFeatureDirectly()` method using reflection
- Implement `updateReplacerInRegistry()` method
- Handle registry backing map access safely

### Phase 2: Tree Registration Enhancement

#### 2.1 Modify TreeApiHandler.handleSave()
**File**: `TreeApiHandler.java`
- After saving JSON file, parse and register feature directly in registry
- Add error handling with fallback to file-only saving
- Track registered features in TreeEngine.customTrees

#### 2.2 Add Tree Deletion Handling
**File**: `TreeApiHandler.java`
- Modify `handleDelete()` to remove from registry if present
- Clean up tracking maps

### Phase 3: Replacer Updates

#### 3.1 Enhance TreeReplacerManager.saveReplacer()
**File**: `TreeReplacerManager.java`
- After generating datapack file, update in-memory registry
- Create/modify simple_random_selector features using reflection
- Track active replacers in TreeEngine.activeReplacers

#### 3.2 Add Replacer Deletion Handling
**File**: `TreeReplacerManager.java`
- Modify `delete()` to remove from registry
- Clean up tracking maps

### Phase 4: Reload Command

#### 4.1 Add Reload Functionality
**File**: `TreeEngineCommand.java`
- Add `/tree_engine reload` command (separate from web reload)
- Implement `reloadTrees()` method to reload all custom trees from JSON
- Implement `reloadAllReplacers()` method

#### 4.2 Add Reload API Endpoint
**File**: `TreeApiHandler.java`
- Add `POST /api/hot-reload` endpoint
- Trigger full reload of trees and replacers
- Return success/failure status

### Phase 5: Web Interface Integration

#### 5.1 Update Tree Manager
**File**: `src/main/resources/web/js/components/tree-manager.js`
- After successful save, call hot-reload endpoint
- Show user feedback about hot reloading

#### 5.2 Update Replacer Manager
**File**: `src/main/resources/web/js/components/tree-replacer.js`
- After successful replacer save, call hot-reload endpoint

### Phase 6: Error Handling and Safety

#### 6.1 Add Validation
- Validate JSON before attempting registry registration
- Provide detailed error messages for debugging
- Fallback to file-only mode if reflection fails

#### 6.2 Thread Safety
- Ensure registry modifications happen on main server thread
- Use proper synchronization for tracking maps

#### 6.3 Memory Management
- Prevent memory leaks by cleaning up old features
- Monitor registry size and performance impact

## Technical Considerations

### Corrected Approach: In-Place Object Modification

**Key Insight from Original Documentation**: The Savs Better Trees approach doesn't modify the registry map itself, but modifies the existing objects within the registry using reflection.

**Correct Implementation**:
1. **For Existing Features**: Get the feature from registry, modify its config object directly using reflection
2. **For New Features**: Track them for loading on server restart
3. **For Replacers**: Modify existing simple_random_selector config objects in-place

**Current Implementation Status**:
- ✅ **Existing Tree Modification**: Modifies TreeFeatureConfig objects directly in registry
- ✅ **New Tree Tracking**: Tracks new trees for restart loading
- ✅ **Replacer Modification**: Modifies RandomFeatureConfig objects in-place
- ✅ **File Persistence**: All changes saved to JSON files
- 🔄 **Testing Needed**: Verify that existing tree modifications work in-game

### Technical Details

**Tree Feature Modification**:
```java
// Get existing feature from registry
var existingFeature = registry.getEntry(id).get().value();
var existingConfig = (TreeFeatureConfig) existingFeature.config();

// Modify config fields directly using reflection
modifyTreeFeatureConfig(existingConfig, newConfig);
```

**Replacer Modification**:
```java
// Modify existing RandomFeatureConfig in-place
modifyRandomFeatureConfig(existingConfig, newConfig);
```

### Performance Impact
- Tracking maps have minimal memory overhead
- File operations are lightweight
- No impact on chunk generation performance

## Testing Plan

### Unit Tests
1. Test reflection utilities with mock registries
2. Test JSON parsing and feature registration
3. Test replacer creation and updates

### Integration Tests
1. Save tree via API, verify registry contains feature
2. Save replacer, verify vanilla tree uses custom pool
3. Delete tree/replacer, verify cleanup
4. Test reload command functionality

### Manual Testing Scenarios
1. Create new tree, generate chunks, verify appearance
2. Modify existing tree, generate new chunks, verify changes
3. Create replacer, verify vanilla trees use custom variants
4. Update replacer pool, verify immediate effect
5. Test error handling with invalid JSON

### Compatibility Testing
1. Verify works with existing datapacks
2. Test with other mods that modify world generation
3. Verify persistence across server restarts

## Risks and Mitigations

### Risk: Reflection Breaks with Minecraft Updates
**Mitigation**: 
- Extensive logging of field access attempts
- Fallback to file-only mode with clear user warnings
- Version-specific code paths if needed

### Risk: Registry Corruption
**Mitigation**:
- Validate features before registration
- Keep backup references to prevent garbage collection
- Test thoroughly before production use

### Risk: Thread Safety Issues
**Mitigation**:
- All registry modifications on main thread
- Use thread-safe collections for tracking maps
- Proper synchronization where needed

### Risk: Memory Leaks
**Mitigation**:
- Clean up old features when replaced
- Monitor memory usage in testing
- Implement cleanup on mod disable

### Risk: Performance Degradation
**Mitigation**:
- Profile registry access patterns
- Limit scope to custom features only
- Monitor chunk generation performance

## Implementation Timeline

### Week 1: Core Infrastructure
- Add tracking maps and reflection utilities
- Basic tree registration enhancement

### Week 2: Replacer Updates
- Enhance replacer manager
- Add reload command

### Week 3: Web Integration
- Update frontend to trigger hot reload
- Add API endpoint

### Week 4: Testing and Refinement
- Comprehensive testing
- Error handling improvements
- Performance optimization

## Success Criteria

1. **Immediate Feedback**: Users see tree changes in new chunks without restart
2. **Persistence**: JSON files still saved for server restarts
3. **Reliability**: Graceful fallback if reflection fails
4. **Performance**: No significant impact on server performance
5. **Compatibility**: Works with existing datapack system and other mods

## Rollback Plan

If hot reloading proves unstable:
1. Disable reflection-based registration via config flag
2. Fall back to file-only saving with restart requirement
3. Keep existing datapack generation intact
4. User can toggle feature on/off without code changes

This implementation provides the hot reloading behavior users expect while maintaining system stability and backward compatibility.