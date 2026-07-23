package instance

import (
	"os"
	"path/filepath"
)

const appFolderName = "TreeEngineLauncher"

// Layout describes every path the launcher manages. Everything lives under
// the user's local app data directory - the user never chooses or sees
// these paths, matching the "zero configuration" goal.
type Layout struct {
	Root         string // %LOCALAPPDATA%\TreeEngineLauncher
	InstanceDir  string // Root\instance (Minecraft server working directory)
	RuntimeDir   string // Root\runtime (private downloaded JRE, if needed)
	StateFile    string // Root\launcher-state.json
	SettingsFile string // Root\settings.json
}

// DefaultLayout resolves the standard launcher directory layout.
func DefaultLayout() (Layout, error) {
	base, err := os.UserCacheDir() // %LOCALAPPDATA% on Windows
	if err != nil {
		return Layout{}, err
	}
	root := filepath.Join(base, appFolderName)
	return Layout{
		Root:         root,
		InstanceDir:  filepath.Join(root, "instance"),
		RuntimeDir:   filepath.Join(root, "runtime"),
		StateFile:    filepath.Join(root, "launcher-state.json"),
		SettingsFile: filepath.Join(root, "settings.json"),
	}, nil
}

func (l Layout) ModsDir() string {
	return filepath.Join(l.InstanceDir, "mods")
}

// McAssetsDir is the cache root for extracted vanilla Minecraft client assets
// (block models, blockstates, textures), keyed by Minecraft version beneath it.
// Served to the webview by the launcher's asset server; consumed by the
// deepslate renderer. Not part of the Minecraft server instance.
func (l Layout) McAssetsDir() string {
	return filepath.Join(l.Root, "mcassets")
}

func (l Layout) TreeEngineConfigDir() string {
	return filepath.Join(l.InstanceDir, "config", "tree_engine")
}

func (l Layout) ServerJarPath() string {
	return filepath.Join(l.InstanceDir, "fabric-server-launch.jar")
}

func (l Layout) ServerPropertiesPath() string {
	return filepath.Join(l.InstanceDir, "server.properties")
}

func (l Layout) EnsureDirs() error {
	dirs := []string{l.Root, l.InstanceDir, l.ModsDir(), l.TreeEngineConfigDir()}
	for _, d := range dirs {
		if err := os.MkdirAll(d, 0o755); err != nil {
			return err
		}
	}
	return nil
}
