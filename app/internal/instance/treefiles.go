// Tree and placement files inside an open project.
//
// The backend mod is stateless: it compiles datapacks handed to it over HTTP
// and never touches disk. That makes this package the only owner of a
// project's files, which is why tree CRUD lives here rather than behind an
// API call. It also means "save" is a local file write - fast, and it works
// whether or not the server happens to be running.
package instance

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
)

// TreeNamespace is the namespace the editor writes its own trees under.
const TreeNamespace = "tree_engine"

// validTreeID guards the path we build from an id. Ids come from the editor,
// but a malformed one must never be able to escape the project folder.
var validTreeID = regexp.MustCompile(`^[a-z0-9_.-]{1,64}$`)

// ValidateTreeID reports whether id is safe to use as a file name.
func ValidateTreeID(id string) error {
	if !validTreeID.MatchString(id) {
		return fmt.Errorf("invalid tree id %q: use lowercase letters, digits, _, . or - (max 64)", id)
	}
	return nil
}

// ConfiguredFeatureDir is data/<ns>/worldgen/configured_feature in a project.
func ConfiguredFeatureDir(projectPath string) string {
	return filepath.Join(projectPath, "data", TreeNamespace, "worldgen", "configured_feature")
}

// PlacedFeatureDir is data/<ns>/worldgen/placed_feature in a project.
func PlacedFeatureDir(projectPath string) string {
	return filepath.Join(projectPath, "data", TreeNamespace, "worldgen", "placed_feature")
}

// ListTrees returns the ids of every configured feature the project defines,
// sorted. A project with no trees yet is not an error.
func ListTrees(projectPath string) ([]string, error) {
	dir := ConfiguredFeatureDir(projectPath)
	entries, err := os.ReadDir(dir)
	if os.IsNotExist(err) {
		return []string{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("failed to read trees: %w", err)
	}

	ids := make([]string, 0, len(entries))
	for _, e := range entries {
		if e.IsDir() || !strings.HasSuffix(e.Name(), ".json") {
			continue
		}
		ids = append(ids, strings.TrimSuffix(e.Name(), ".json"))
	}
	sort.Strings(ids)
	return ids, nil
}

// ReadTree returns a tree's raw ConfiguredFeature JSON.
func ReadTree(projectPath, id string) (string, error) {
	if err := ValidateTreeID(id); err != nil {
		return "", err
	}
	data, err := os.ReadFile(filepath.Join(ConfiguredFeatureDir(projectPath), id+".json"))
	if err != nil {
		if os.IsNotExist(err) {
			return "", fmt.Errorf("no such tree: %s", id)
		}
		return "", fmt.Errorf("failed to read tree %s: %w", id, err)
	}
	return string(data), nil
}

// WriteTree saves a tree's ConfiguredFeature JSON, and creates a matching
// PlacedFeature if one does not exist yet.
//
// The placed feature matters even for a tree the user never places by hand:
// it is what lets the tree be referenced from a replacer pool or a biome, and
// creating it lazily here means the editor never has to think about it.
func WriteTree(projectPath, id, featureJSON string) error {
	if err := ValidateTreeID(id); err != nil {
		return err
	}
	if !json.Valid([]byte(featureJSON)) {
		return fmt.Errorf("tree %s is not valid JSON", id)
	}

	dir := ConfiguredFeatureDir(projectPath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("failed to create tree folder: %w", err)
	}
	if err := writeFileAtomic(filepath.Join(dir, id+".json"), []byte(featureJSON)); err != nil {
		return fmt.Errorf("failed to save tree %s: %w", id, err)
	}

	placedDir := PlacedFeatureDir(projectPath)
	placedPath := filepath.Join(placedDir, id+".json")
	if _, err := os.Stat(placedPath); os.IsNotExist(err) {
		if err := os.MkdirAll(placedDir, 0o755); err != nil {
			return fmt.Errorf("failed to create placement folder: %w", err)
		}
		if err := writeFileAtomic(placedPath, []byte(defaultPlacement(id))); err != nil {
			return fmt.Errorf("failed to create placement for %s: %w", id, err)
		}
	}
	return nil
}

// DeleteTree removes a tree and its placement. Missing files are not an error.
func DeleteTree(projectPath, id string) error {
	if err := ValidateTreeID(id); err != nil {
		return err
	}
	if err := os.Remove(filepath.Join(ConfiguredFeatureDir(projectPath), id+".json")); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to delete tree %s: %w", id, err)
	}
	if err := os.Remove(filepath.Join(PlacedFeatureDir(projectPath), id+".json")); err != nil && !os.IsNotExist(err) {
		return fmt.Errorf("failed to delete placement for %s: %w", id, err)
	}
	return nil
}

// ReadPlacement returns a tree's PlacedFeature JSON, synthesising the default
// if the file is absent rather than failing - a tree with no placement rules
// is a normal state, not a broken one.
func ReadPlacement(projectPath, id string) (string, error) {
	if err := ValidateTreeID(id); err != nil {
		return "", err
	}
	data, err := os.ReadFile(filepath.Join(PlacedFeatureDir(projectPath), id+".json"))
	if os.IsNotExist(err) {
		return defaultPlacement(id), nil
	}
	if err != nil {
		return "", fmt.Errorf("failed to read placement for %s: %w", id, err)
	}
	return string(data), nil
}

// WritePlacement saves a tree's PlacedFeature JSON.
func WritePlacement(projectPath, id, placementJSON string) error {
	if err := ValidateTreeID(id); err != nil {
		return err
	}
	if !json.Valid([]byte(placementJSON)) {
		return fmt.Errorf("placement for %s is not valid JSON", id)
	}
	dir := PlacedFeatureDir(projectPath)
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return fmt.Errorf("failed to create placement folder: %w", err)
	}
	if err := writeFileAtomic(filepath.Join(dir, id+".json"), []byte(placementJSON)); err != nil {
		return fmt.Errorf("failed to save placement for %s: %w", id, err)
	}
	return nil
}

func defaultPlacement(id string) string {
	return fmt.Sprintf("{\n  \"feature\": \"%s:%s\",\n  \"placement\": []\n}\n", TreeNamespace, id)
}

// ReadDatapack collects every file under the project's data/ folder as
// datapack-relative path -> contents, which is exactly the payload the
// backend's /v1/session endpoint takes.
func ReadDatapack(projectPath string) (map[string]string, error) {
	root := filepath.Join(projectPath, "data")
	files := map[string]string{}

	err := filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			if os.IsNotExist(err) {
				return nil
			}
			return err
		}
		if info.IsDir() || !strings.HasSuffix(info.Name(), ".json") {
			return nil
		}
		rel, err := filepath.Rel(projectPath, path)
		if err != nil {
			return err
		}
		data, err := os.ReadFile(path)
		if err != nil {
			return err
		}
		// The backend addresses resources with forward slashes regardless of
		// the host OS.
		files[filepath.ToSlash(rel)] = string(data)
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("failed to read project datapack: %w", err)
	}
	return files, nil
}

// writeFileAtomic writes via a temp file and renames, so an interrupted save
// cannot leave a half-written tree behind for the backend to choke on.
func writeFileAtomic(path string, data []byte) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName)

	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmpName, path)
}
