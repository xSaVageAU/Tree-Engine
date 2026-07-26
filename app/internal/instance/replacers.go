// Tree replacers, as datapack authoring.
//
// A replacer makes a vanilla tree generate one of your trees instead. The old
// implementation did this by reflecting into the running game's registries at
// runtime; this one simply writes the datapack file that produces the same
// result - a configured_feature under the vanilla namespace that shadows the
// vanilla id with a random selector over your trees.
//
// That is a strictly better fit: the output is a real datapack you can ship,
// it survives a restart, and the chunk preview renders it because the backend
// compiles the same files the game would load.
package instance

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// Replacer describes one vanilla tree being replaced by a pool of others.
type Replacer struct {
	// VanillaID is the configured feature being shadowed, e.g. "minecraft:oak".
	VanillaID string `json:"vanillaId"`
	// Entries are the replacements. A single entry replaces unconditionally.
	Entries []ReplacerEntry `json:"entries"`
	// Mode is how the editor presented this pool - "weighted" (explicit
	// chances) or "simple" (equal odds). It has no effect on the generated
	// datapack, which is always a random_selector; it is recorded so reopening
	// a replacer shows the same form the user built it with.
	Mode string `json:"mode,omitempty"`
}

// ReplacerEntry is one candidate in a replacer's pool.
type ReplacerEntry struct {
	// Feature is a placed feature id, e.g. "tree_engine:my_oak".
	Feature string `json:"feature"`
	// Chance is this entry's probability (0-1). The last entry acts as the
	// fallback and its chance is ignored, matching how Minecraft's
	// random_selector works.
	Chance float64 `json:"chance"`
}

// replacerManifest records which vanilla ids this project has replaced, so the
// editor can list and edit them. Minecraft never reads it; it lives outside
// data/ precisely so it is not mistaken for datapack content.
const replacerManifestName = "tree-engine.replacers.json"

func replacerManifestPath(projectPath string) string {
	return filepath.Join(projectPath, replacerManifestName)
}

// vanillaFeatureDir is where a shadowing configured_feature must live: under
// the *replaced* id's namespace, so it overrides the original.
func vanillaFeatureDir(projectPath, namespace string) string {
	return filepath.Join(projectPath, "data", namespace, "worldgen", "configured_feature")
}

// ListReplacers returns the project's replacers, sorted by the id they shadow.
func ListReplacers(projectPath string) ([]Replacer, error) {
	data, err := os.ReadFile(replacerManifestPath(projectPath))
	if os.IsNotExist(err) {
		return []Replacer{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to read replacers: %w", err)
	}
	var replacers []Replacer
	if err := json.Unmarshal(data, &replacers); err != nil {
		return nil, fmt.Errorf("replacer manifest is corrupt: %w", err)
	}
	sort.Slice(replacers, func(i, j int) bool {
		return replacers[i].VanillaID < replacers[j].VanillaID
	})
	return replacers, nil
}

// SaveReplacer writes the shadowing datapack file and records the replacer.
func SaveReplacer(projectPath string, r Replacer) error {
	namespace, name, err := splitID(r.VanillaID)
	if err != nil {
		return err
	}
	if len(r.Entries) == 0 {
		return fmt.Errorf("replacer for %s needs at least one tree", r.VanillaID)
	}
	for _, e := range r.Entries {
		if _, _, err := splitID(e.Feature); err != nil {
			return fmt.Errorf("replacer for %s: %w", r.VanillaID, err)
		}
	}

	selector, err := buildRandomSelector(r)
	if err != nil {
		return err
	}

	dir := vanillaFeatureDir(projectPath, namespace)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("failed to create folder for %s: %w", r.VanillaID, err)
	}
	if err := writeFileAtomic(filepath.Join(dir, name+".json"), selector); err != nil {
		return fmt.Errorf("failed to write replacer for %s: %w", r.VanillaID, err)
	}

	return updateManifest(projectPath, func(all []Replacer) []Replacer {
		for i := range all {
			if all[i].VanillaID == r.VanillaID {
				all[i] = r
				return all
			}
		}
		return append(all, r)
	})
}

// DeleteReplacer removes the shadowing file, restoring the vanilla tree.
func DeleteReplacer(projectPath, vanillaID string) error {
	namespace, name, err := splitID(vanillaID)
	if err != nil {
		return err
	}
	path := filepath.Join(vanillaFeatureDir(projectPath, namespace), name+".json")
	if err := os.Remove(path); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to remove replacer for %s: %w", vanillaID, err)
	}
	return updateManifest(projectPath, func(all []Replacer) []Replacer {
		out := all[:0]
		for _, r := range all {
			if r.VanillaID != vanillaID {
				out = append(out, r)
			}
		}
		return out
	})
}

// buildRandomSelector produces the configured feature that shadows a vanilla
// id. minecraft:random_selector takes weighted features plus a default, which
// is the shape a replacer pool maps onto directly.
func buildRandomSelector(r Replacer) ([]byte, error) {
	type weighted struct {
		Feature string  `json:"feature"`
		Chance  float64 `json:"chance"`
	}
	type config struct {
		Features []weighted `json:"features"`
		Default  string     `json:"default"`
	}
	type selector struct {
		Type   string `json:"type"`
		Config config `json:"config"`
	}

	// The final entry is the fallback; everything before it is chance-based.
	fallback := r.Entries[len(r.Entries)-1].Feature
	features := make([]weighted, 0, len(r.Entries)-1)
	for _, e := range r.Entries[:len(r.Entries)-1] {
		chance := e.Chance
		if chance <= 0 || chance > 1 {
			return nil, fmt.Errorf("chance for %s must be between 0 and 1, got %v", e.Feature, chance)
		}
		features = append(features, weighted{Feature: e.Feature, Chance: chance})
	}

	out, err := json.MarshalIndent(selector{
		Type:   "minecraft:random_selector",
		Config: config{Features: features, Default: fallback},
	}, "", "  ")
	if err != nil {
		return nil, err
	}
	return append(out, '\n'), nil
}

func updateManifest(projectPath string, mutate func([]Replacer) []Replacer) error {
	existing, err := ListReplacers(projectPath)
	if err != nil {
		return err
	}
	updated := mutate(existing)
	data, err := json.MarshalIndent(updated, "", "  ")
	if err != nil {
		return err
	}
	return writeFileAtomic(replacerManifestPath(projectPath), append(data, '\n'))
}

func splitID(id string) (namespace, name string, err error) {
	parts := strings.SplitN(id, ":", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		return "", "", fmt.Errorf("%q is not a namespaced id like minecraft:oak", id)
	}
	// The name may contain slashes (some packs nest feature ids); reject only
	// what could escape the project folder.
	if strings.Contains(id, "..") {
		return "", "", fmt.Errorf("%q contains an illegal path segment", id)
	}
	if !validTreeID.MatchString(parts[0]) {
		return "", "", fmt.Errorf("%q has an invalid namespace", id)
	}
	return parts[0], parts[1], nil
}
