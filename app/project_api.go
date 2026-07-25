// Wails-bound project file operations.
//
// These exist because the backend mod is stateless: it compiles datapacks it
// is handed and owns no files. Reading and writing the user's project is
// therefore the desktop app's job, and these methods are what the editor
// calls instead of the HTTP tree CRUD the old mod exposed.
package main

import (
	"fmt"

	"app/internal/instance"
)

// activeProject returns the open project's path, or an error naming the
// problem - every method here needs one and the message is the same.
func (a *App) activeProject() (string, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.state == nil || a.state.ActiveProjectPath == "" {
		return "", fmt.Errorf("no project is open")
	}
	return a.state.ActiveProjectPath, nil
}

// ListTrees returns the ids of every tree in the open project.
func (a *App) ListTrees() ([]string, error) {
	path, err := a.activeProject()
	if err != nil {
		return nil, err
	}
	return instance.ListTrees(path)
}

// GetTree returns a tree's ConfiguredFeature JSON as stored on disk.
func (a *App) GetTree(id string) (string, error) {
	path, err := a.activeProject()
	if err != nil {
		return "", err
	}
	return instance.ReadTree(path, id)
}

// SaveTree writes a tree, creating its placement file if needed.
func (a *App) SaveTree(id string, featureJSON string) error {
	path, err := a.activeProject()
	if err != nil {
		return err
	}
	return instance.WriteTree(path, id, featureJSON)
}

// DeleteTree removes a tree and its placement.
func (a *App) DeleteTree(id string) error {
	path, err := a.activeProject()
	if err != nil {
		return err
	}
	return instance.DeleteTree(path, id)
}

// GetPlacement returns a tree's PlacedFeature JSON, defaulting when absent.
func (a *App) GetPlacement(id string) (string, error) {
	path, err := a.activeProject()
	if err != nil {
		return "", err
	}
	return instance.ReadPlacement(path, id)
}

// SavePlacement writes a tree's PlacedFeature JSON.
func (a *App) SavePlacement(id string, placementJSON string) error {
	path, err := a.activeProject()
	if err != nil {
		return err
	}
	return instance.WritePlacement(path, id, placementJSON)
}

// GetProjectDatapack returns the project's data/ tree as path -> contents,
// ready to POST to the backend's /v1/session endpoint. The editor uploads
// this once and then references the session for previews.
func (a *App) GetProjectDatapack() (map[string]string, error) {
	path, err := a.activeProject()
	if err != nil {
		return nil, err
	}
	return instance.ReadDatapack(path)
}

// ListReplacers returns the project's tree replacers.
func (a *App) ListReplacers() ([]instance.Replacer, error) {
	path, err := a.activeProject()
	if err != nil {
		return nil, err
	}
	return instance.ListReplacers(path)
}

// SaveReplacer writes the datapack file that shadows a vanilla tree, and
// records the replacer so the editor can list it again.
func (a *App) SaveReplacer(r instance.Replacer) error {
	path, err := a.activeProject()
	if err != nil {
		return err
	}
	return instance.SaveReplacer(path, r)
}

// DeleteReplacer removes a replacer, restoring the vanilla tree.
func (a *App) DeleteReplacer(vanillaID string) error {
	path, err := a.activeProject()
	if err != nil {
		return err
	}
	return instance.DeleteReplacer(path, vanillaID)
}
