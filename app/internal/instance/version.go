package instance

import (
	"context"
	"os"
	"strings"

	"app/internal/assets"
	"app/internal/fabricmeta"
)

// VersionStatus describes a Minecraft server version and its availability.
type VersionStatus struct {
	GameVersion    string `json:"gameVersion"`
	Stable         bool   `json:"stable"`
	Category       string `json:"category"` // "release", "pre_release", "snapshot"
	IsActive       bool   `json:"isActive"`
	IsDownloaded   bool   `json:"isDownloaded"`
	SupportedByMod bool   `json:"supportedByMod"`
}

func classifyVersion(version string, stable bool) string {
	if stable {
		return "release"
	}
	v := strings.ToLower(version)
	if strings.Contains(v, "-pre") || strings.Contains(v, "-rc") || strings.Contains(v, "pre") || strings.Contains(v, "rc") {
		return "pre_release"
	}
	return "snapshot"
}

// GetAvailableVersions fetches 26.x versions from Fabric Meta and checks local cache status.
func GetAvailableVersions(ctx context.Context, l Layout, activeVersion string) ([]VersionStatus, error) {
	manifest, _ := assets.LoadManifest()
	modTargetVersion := manifest.MinecraftVersion
	if modTargetVersion == "" {
		modTargetVersion = "26.2"
	}

	gameVersions, err := fabricmeta.Fetch26PlusGameVersions(ctx)
	if err != nil || len(gameVersions) == 0 {
		gameVersions = []fabricmeta.GameVersionDetails{
			{Version: "26.2", Stable: true},
			{Version: "26.1.2", Stable: true},
			{Version: "26.1.1", Stable: true},
			{Version: "26.1", Stable: true},
		}
	}

	if activeVersion == "" {
		activeVersion = modTargetVersion
	}

	var res []VersionStatus
	for _, gv := range gameVersions {
		cachedPath := l.VersionServerJarPath(gv.Version)
		_, statErr := os.Stat(cachedPath)
		_, instanceStatErr := os.Stat(l.ServerJarPath())
		isDownloaded := (statErr == nil) || (gv.Version == activeVersion && instanceStatErr == nil)

		res = append(res, VersionStatus{
			GameVersion:    gv.Version,
			Stable:         gv.Stable,
			Category:       classifyVersion(gv.Version, gv.Stable),
			IsActive:       gv.Version == activeVersion,
			IsDownloaded:   isDownloaded,
			SupportedByMod: gv.Version == modTargetVersion,
		})
	}
	return res, nil
}
