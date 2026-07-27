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

// backendConfig mirrors BackendConfig.java. The backend reads this file once
// at startup and never writes it - the launcher owns it outright, which is
// why there is no merge logic here.
//
// Note there is no project path: the backend is stateless and receives
// datapack content in requests, so it has no notion of an "open project".
type backendConfig struct {
	Port          int    `json:"port"`
	Token         string `json:"token"`
	WorkerThreads int    `json:"workerThreads"`
	SessionLimit  int    `json:"sessionLimit"`
	// Where the extracted vanilla colormaps live. Biome grass and foliage
	// colours are sampled from these textures, and a Minecraft server never
	// loads them on its own - they are client assets - so the backend is told
	// where the launcher put them. May not exist yet when this is written;
	// the backend loads them lazily.
	ColormapsDir string `json:"colormapsDir"`
}

// BackendConfigPath is where BackendConfig.CONFIG_FILE looks, relative to the
// server's working directory.
func BackendConfigPath(l Layout) string {
	return filepath.Join(l.InstanceDir, "config", "tree-engine-backend.json")
}

// WriteModConfig pre-seeds the backend's config before the server is launched,
// so it comes up listening on a known port with the launcher's token.
//
// An empty token makes the backend refuse to serve rather than expose an
// unauthenticated API, so this treats a missing token as a programming error.
func WriteModConfig(l Layout, port int, authToken, mcVersion string) error {
	if authToken == "" {
		return fmt.Errorf("refusing to write backend config without an auth token")
	}

	path := BackendConfigPath(l)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return fmt.Errorf("failed to create backend config folder: %w", err)
	}

	data, err := json.MarshalIndent(backendConfig{
		Port:          port,
		Token:         authToken,
		WorkerThreads: 4,
		SessionLimit:  8,
		ColormapsDir:  filepath.Join(l.McAssetsDir(), mcVersion, "textures", "colormap"),
	}, "", "  ")
	if err != nil {
		return err
	}
	// 0600: the file carries the API token.
	return os.WriteFile(path, append(data, '\n'), 0o600)
}

// WriteEula writes eula.txt. Must only be called after the user has
// explicitly agreed to the Minecraft EULA in the UI - this is a real
// agreement gate, not a formality to bypass.
func WriteEula(l Layout) error {
	return os.WriteFile(filepath.Join(l.InstanceDir, "eula.txt"), []byte("eula=true\n"), 0o644)
}

// GenerateAuthToken produces a cryptographically random hex token.
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
