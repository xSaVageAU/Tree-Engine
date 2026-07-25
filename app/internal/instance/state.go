package instance

import (
	"encoding/json"
	"os"
)

// State is the launcher's own record of what it set up, persisted so
// subsequent app launches can skip straight to the control panel instead of
// re-running first-run setup.
type State struct {
	SetupComplete    bool   `json:"setupComplete"`
	JavaPath         string `json:"javaPath"`
	GameVersion      string `json:"gameVersion"`
	LoaderVersion    string `json:"loaderVersion"`
	InstallerVersion string `json:"installerVersion"`
	FabricApiVersion string `json:"fabricApiVersion"`
	ModVersion       string `json:"modVersion"`
	Port             int    `json:"port"`     // the mod's own HTTP API port, not Minecraft's
	GamePort         int    `json:"gamePort"` // Minecraft's server-port (server.properties) - randomized so it never collides with a real Minecraft server on the default 25565
	AuthToken        string `json:"authToken"`

	// ActiveProjectPath is the absolute path to the currently open project
	// folder (the datapack the mod reads/writes directly - see
	// ProjectPaths.java on the mod side). Empty means no project is open;
	// StartServer refuses to boot the server in that state, the same way it
	// already refuses when SetupComplete is false.
	ActiveProjectPath string `json:"activeProjectPath"`
}

// LoadState reads launcher-state.json. A missing file is not an error - it
// just means first-run setup hasn't happened yet (returns SetupComplete=false).
func LoadState(path string) (*State, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return &State{}, nil
	}
	if err != nil {
		return nil, err
	}
	var s State
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

func (s *State) Save(path string) error {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}
