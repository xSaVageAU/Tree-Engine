// Package assets embeds the built Tree Engine backend jar and its metadata
// manifest, both populated by scripts/sync-backend-jar.ps1. Never edit
// tree-engine.jar or mod-manifest.json by hand - re-run the sync script
// after changing the backend.
package assets

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"fmt"
)

//go:embed tree-engine.jar
var TreeEngineJar []byte

//go:embed mod-manifest.json
var modManifestRaw []byte

// ModManifest describes the exact backend build embedded above, read from
// backend/gradle.properties at sync time so the Go code never hardcodes
// Java build metadata that could drift out of sync.
type ModManifest struct {
	MinecraftVersion string `json:"minecraftVersion"`
	FabricApiVersion string `json:"fabricApiVersion"`
	ModVersion       string `json:"modVersion"`
}

// LoadManifest parses the embedded mod-manifest.json.
func LoadManifest() (ModManifest, error) {
	var m ModManifest
	raw := bytes.TrimPrefix(modManifestRaw, []byte{0xEF, 0xBB, 0xBF}) // strip UTF-8 BOM, if present
	if err := json.Unmarshal(raw, &m); err != nil {
		return m, fmt.Errorf("failed to parse embedded mod manifest: %w", err)
	}
	return m, nil
}
