// Project folders: the "workspace" a user opens, VS Code style. Unlike the
// datapack import flow in datapacks.go (which copies a third-party pack into
// the managed instance), a project folder lives wherever the user keeps it and
// is read and written by this app alone - the backend never touches disk, it
// receives the project's contents in a request. This file handles opening
// one: sanity-checking the chosen path and scaffolding it into a valid
// datapack if it isn't one yet.
package instance

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// ValidateProjectPath rejects folder choices that would be actively broken -
// pointing back at the launcher's own managed instance (self-reference,
// would collide with the mod's own config/server files) or a path that isn't
// even a directory.
func ValidateProjectPath(l Layout, path string) error {
	info, err := os.Stat(path)
	if err != nil {
		return fmt.Errorf("can't access %q: %w", path, err)
	}
	if !info.IsDir() {
		return fmt.Errorf("%q is not a folder", path)
	}

	absPath, err := filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("failed to resolve %q: %w", path, err)
	}
	absInstance, err := filepath.Abs(l.InstanceDir)
	if err != nil {
		return fmt.Errorf("failed to resolve instance dir: %w", err)
	}
	rel, err := filepath.Rel(absInstance, absPath)
	if err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) {
		return fmt.Errorf("can't open a project inside the app's own managed server folder")
	}

	return nil
}

// projectPackMcmeta must stay byte-compatible with the JSON
// TreeEngineResourcePackProvider.createDefaultDatapack() generates on the mod
// side, so a project scaffolded from either side looks identical.
const projectPackMcmeta = `{
  "pack": {
    "pack_format": 88,
    "supported_formats": {
      "min_inclusive": 88,
      "max_inclusive": 88
    },
    "description": "Tree Engine Datapack"
  }
}`

// ScaffoldProjectIfNeeded initializes path as a Tree Engine datapack if it
// isn't already one - creating pack.mcmeta plus the configured_feature/
// placed_feature folders treefiles.go writes into. Safe to call on an
// already-valid project folder (a no-op past the directory checks).
func ScaffoldProjectIfNeeded(path string) error {
	configuredFeatureDir := filepath.Join(path, "data", "tree_engine", "worldgen", "configured_feature")
	placedFeatureDir := filepath.Join(path, "data", "tree_engine", "worldgen", "placed_feature")

	if err := os.MkdirAll(configuredFeatureDir, 0o755); err != nil {
		return fmt.Errorf("failed to create configured_feature folder: %w", err)
	}
	if err := os.MkdirAll(placedFeatureDir, 0o755); err != nil {
		return fmt.Errorf("failed to create placed_feature folder: %w", err)
	}

	mcmetaPath := filepath.Join(path, "pack.mcmeta")
	if _, err := os.Stat(mcmetaPath); os.IsNotExist(err) {
		if err := os.WriteFile(mcmetaPath, []byte(projectPackMcmeta), 0o644); err != nil {
			return fmt.Errorf("failed to write pack.mcmeta: %w", err)
		}
	}

	return nil
}
