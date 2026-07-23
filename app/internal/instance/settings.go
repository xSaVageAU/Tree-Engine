package instance

import (
	"encoding/json"
	"os"
)

// Settings holds user-configurable launcher preferences - distinct from
// State, which records what first-run setup auto-detected/installed. This is
// where future options (Minecraft version, Java path override, etc.) belong.
type Settings struct {
	AutoStartOnLaunch bool `json:"autoStartOnLaunch"`
}

// LoadSettings reads settings.json. A missing file is not an error - it just
// means the user hasn't changed anything from the defaults yet.
func LoadSettings(path string) (*Settings, error) {
	data, err := os.ReadFile(path)
	if os.IsNotExist(err) {
		return &Settings{}, nil
	}
	if err != nil {
		return nil, err
	}
	var s Settings
	if err := json.Unmarshal(data, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

func (s *Settings) Save(path string) error {
	data, err := json.MarshalIndent(s, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(path, data, 0o644)
}
