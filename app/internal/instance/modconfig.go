package instance

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"path/filepath"
)

// treeEngineConfig mirrors the fields MainConfig.java reads/writes
// (mod/src/main/java/savage/tree_engine/config/MainConfig.java). The mod's
// lenient JSON reader accepts this plain (comment-free) form fine, and
// immediately rewrites it into its own commented format on first load.
type treeEngineConfig struct {
	ServerPort            int    `json:"server_port"`
	AutoStartWebOnBoot    bool   `json:"auto_start_web_on_boot"`
	AuthToken             string `json:"auth_token"`
	AuthEnabled           bool   `json:"auth_enabled"`
	RegenerateTokenOnBoot bool   `json:"regenerate_token_on_restart"`
	HotReloadEnabled      bool   `json:"hot_reload_enabled"`
	TreeGenerationThreads int    `json:"tree_generation_threads"`
	ActiveProjectDir      string `json:"active_project_dir"`
}

// WriteModConfig pre-seeds config/tree_engine/config.json before the server
// is ever launched, so the mod auto-starts its web editor and reuses the
// launcher-generated token instead of requiring a manual command + copy-paste.
// activeProjectDir is the currently open project folder (see
// ProjectPaths.java on the mod side) - empty means none open.
func WriteModConfig(l Layout, port int, authToken string, activeProjectDir string) error {
	cfg := treeEngineConfig{
		ServerPort:            port,
		AutoStartWebOnBoot:    true,
		AuthToken:             authToken,
		AuthEnabled:           true,
		RegenerateTokenOnBoot: false, // keep the token stable across restarts
		HotReloadEnabled:      true,
		TreeGenerationThreads: 4,
		ActiveProjectDir:      activeProjectDir,
	}
	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(filepath.Join(l.TreeEngineConfigDir(), "config.json"), data, 0o644)
}

// WriteEula writes eula.txt. Must only be called after the user has
// explicitly agreed to the Minecraft EULA in the UI - this is a real
// agreement gate, not a formality to bypass.
func WriteEula(l Layout) error {
	return os.WriteFile(filepath.Join(l.InstanceDir, "eula.txt"), []byte("eula=true\n"), 0o644)
}

// GenerateAuthToken produces a cryptographically random hex token, matching
// the format AuthenticationManager.java itself generates.
func GenerateAuthToken() (string, error) {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		return "", fmt.Errorf("failed to generate auth token: %w", err)
	}
	return hex.EncodeToString(b), nil
}

// FindFreePort probes for an available TCP port starting at preferred,
// falling back to the OS-assigned ephemeral port if none of the first 20
// candidates are free.
func FindFreePort(preferred int) (int, error) {
	for p := preferred; p < preferred+20; p++ {
		if isPortFree(p) {
			return p, nil
		}
	}
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, fmt.Errorf("failed to find a free port: %w", err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port, nil
}

func isPortFree(port int) bool {
	l, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		return false
	}
	l.Close()
	return true
}
