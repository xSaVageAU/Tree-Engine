// Package modrinth fetches the Fabric API mod jar from Modrinth. Fabric API
// is a modImplementation dependency of the Tree Engine mod (see
// mod/build.gradle) - it is NOT bundled into tree-engine.jar, so it must be
// downloaded and placed in mods/ alongside it at runtime.
package modrinth

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
)

const projectVersionsURL = "https://api.modrinth.com/v2/project/fabric-api/version"

type modrinthFile struct {
	URL     string `json:"url"`
	Primary bool   `json:"primary"`
}

type modrinthVersion struct {
	VersionNumber string         `json:"version_number"`
	Files         []modrinthFile `json:"files"`
}

// FindFabricApiJar looks up Fabric API builds for the given Minecraft
// version. If versionHint is non-empty, an exact version_number match is
// preferred; otherwise (or if no exact match exists) the newest listed build
// for that game version is used. Returns the direct download URL.
func FindFabricApiJar(ctx context.Context, gameVersion, versionHint string) (downloadURL string, resolvedVersion string, err error) {
	q := url.Values{}
	q.Set("loaders", `["fabric"]`)
	q.Set("game_versions", fmt.Sprintf(`["%s"]`, gameVersion))

	reqURL := projectVersionsURL + "?" + q.Encode()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, reqURL, nil)
	if err != nil {
		return "", "", err
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", "", fmt.Errorf("failed to query modrinth: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", "", fmt.Errorf("unexpected status from modrinth: %s", resp.Status)
	}

	var versions []modrinthVersion
	if err := json.NewDecoder(resp.Body).Decode(&versions); err != nil {
		return "", "", fmt.Errorf("failed to decode modrinth response: %w", err)
	}
	if len(versions) == 0 {
		return "", "", fmt.Errorf("no Fabric API build found for Minecraft %s", gameVersion)
	}

	pick := func(v modrinthVersion) (string, string, error) {
		for _, f := range v.Files {
			if f.Primary {
				return f.URL, v.VersionNumber, nil
			}
		}
		if len(v.Files) > 0 {
			return v.Files[0].URL, v.VersionNumber, nil
		}
		return "", "", fmt.Errorf("fabric api version %s has no downloadable files", v.VersionNumber)
	}

	if versionHint != "" {
		for _, v := range versions {
			if v.VersionNumber == versionHint {
				return pick(v)
			}
		}
	}

	// Modrinth returns newest-first; fall back to the latest build for this game version.
	return pick(versions[0])
}

// Download fetches the given URL and writes it to destPath.
func Download(ctx context.Context, fileURL, destPath string) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, fileURL, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to download %s: %w", fileURL, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status downloading %s: %s", fileURL, resp.Status)
	}

	out, err := os.Create(destPath)
	if err != nil {
		return err
	}
	defer out.Close()

	if _, err := io.Copy(out, resp.Body); err != nil {
		return fmt.Errorf("failed to write %s: %w", destPath, err)
	}
	return nil
}
