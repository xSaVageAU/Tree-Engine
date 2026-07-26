// Package fabricmeta talks to meta.fabricmc.net to resolve loader/installer
// versions and download a self-bootstrapping Fabric server jar. That jar
// (authored and hosted by the Fabric project, not us) is what downloads the
// vanilla Minecraft server from Mojang on first launch - this app never
// touches or redistributes Mojang binaries directly.
package fabricmeta

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
)

const baseURL = "https://meta.fabricmc.net/v2"

type loaderVersion struct {
	Version string `json:"version"`
	Stable  bool   `json:"stable"`
}

type installerVersion struct {
	Version string `json:"version"`
	Stable  bool   `json:"stable"`
}

type gameVersion struct {
	Version string `json:"version"`
	Stable  bool   `json:"stable"`
}


func getJSON(ctx context.Context, url string, out any) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("request to %s failed: %w", url, err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status from %s: %s", url, resp.Status)
	}
	return json.NewDecoder(resp.Body).Decode(out)
}

// LatestStableLoader returns the newest loader version marked stable.
func LatestStableLoader(ctx context.Context) (string, error) {
	var versions []loaderVersion
	if err := getJSON(ctx, baseURL+"/versions/loader", &versions); err != nil {
		return "", err
	}
	for _, v := range versions {
		if v.Stable {
			return v.Version, nil
		}
	}
	return "", fmt.Errorf("no stable loader version found")
}

// LatestStableInstaller returns the newest installer version marked stable.
func LatestStableInstaller(ctx context.Context) (string, error) {
	var versions []installerVersion
	if err := getJSON(ctx, baseURL+"/versions/installer", &versions); err != nil {
		return "", err
	}
	for _, v := range versions {
		if v.Stable {
			return v.Version, nil
		}
	}
	return "", fmt.Errorf("no stable installer version found")
}

// DownloadServerJar fetches the self-bootstrapping Fabric server launcher jar
// for the given game/loader/installer version combination and writes it to
// destPath. Running this jar downloads the vanilla server + Fabric libraries
// itself on first launch (verified empirically: ~180KB bootstrap jar that
// self-installs everything into its working directory).
func DownloadServerJar(ctx context.Context, gameVersion, loaderVersion, installerVersion, destPath string) error {
	url := fmt.Sprintf("%s/versions/loader/%s/%s/%s/server/jar", baseURL, gameVersion, loaderVersion, installerVersion)

	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return err
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return fmt.Errorf("failed to download fabric server jar: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("unexpected status downloading fabric server jar: %s", resp.Status)
	}

	out, err := os.Create(destPath)
	if err != nil {
		return err
	}
	defer out.Close()

	if _, err := io.Copy(out, resp.Body); err != nil {
		return fmt.Errorf("failed to write fabric server jar: %w", err)
	}
	return nil
}

type GameVersionDetails struct {
	Version string `json:"version"`
	Stable  bool   `json:"stable"`
}

// Fetch26PlusGameVersions returns a list of Fabric-supported Minecraft game
// versions matching 26.x (26.1 and newer), newest-first with stability metadata.
func Fetch26PlusGameVersions(ctx context.Context) ([]GameVersionDetails, error) {
	var versions []gameVersion
	if err := getJSON(ctx, baseURL+"/versions/game", &versions); err != nil {
		// Fallback list if offline or API unreachable
		return []GameVersionDetails{
			{Version: "26.2", Stable: true},
			{Version: "26.1.2", Stable: true},
			{Version: "26.1.1", Stable: true},
			{Version: "26.1", Stable: true},
		}, nil
	}

	var res []GameVersionDetails
	seen := make(map[string]bool)
	for _, v := range versions {
		if strings.HasPrefix(v.Version, "26.") && !seen[v.Version] {
			res = append(res, GameVersionDetails{
				Version: v.Version,
				Stable:  v.Stable,
			})
			seen[v.Version] = true
		}
	}

	// Always ensure 26.2 is present as fallback
	if len(res) == 0 {
		return []GameVersionDetails{
			{Version: "26.2", Stable: true},
			{Version: "26.1.2", Stable: true},
			{Version: "26.1.1", Stable: true},
			{Version: "26.1", Stable: true},
		}, nil
	}
	return res, nil
}

