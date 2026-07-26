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

	// RecentProjects is a most-recent-first, de-duplicated list of project
	// folders previously opened, for the "Open Recent" list on the
	// needs-project screen. Capped at recentProjectsLimit.
	RecentProjects []string `json:"recentProjects"`
}

const recentProjectsLimit = 8

// WithRecentProject returns a copy of RecentProjects with path moved to the
// front (or inserted there), de-duplicated, and capped at
// recentProjectsLimit. Doesn't mutate the receiver's slice in place so
// callers can assign the result back under whatever lock they hold.
func (s *Settings) WithRecentProject(path string) []string {
	next := make([]string, 0, len(s.RecentProjects)+1)
	next = append(next, path)
	for _, p := range s.RecentProjects {
		if p != path {
			next = append(next, p)
		}
	}
	if len(next) > recentProjectsLimit {
		next = next[:recentProjectsLimit]
	}
	return next
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
