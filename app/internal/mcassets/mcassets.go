// Package mcassets provisions the vanilla Minecraft *client* assets needed to
// render blocks accurately: block state definitions, block models, block
// textures, and colormaps. These live inside the versioned client.jar, which
// is downloaded on the user's machine at runtime directly from Mojang's
// official CDN and never bundled or redistributed by this app - the same
// "bring your own Mojang binaries" stance the Fabric server download takes
// (see internal/fabricmeta).
//
// The renderer (deepslate, in the frontend) is data-driven: it reads these
// JSON model/blockstate files rather than hardcoding per-block logic, so once
// the assets for a Minecraft version are present every block renders the way
// the game draws it - with no user-supplied resource pack required.
package mcassets

import (
	"archive/zip"
	"context"
	"crypto/sha1"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
)

const versionManifestURL = "https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"

// readyMarker is written into a version's asset dir once extraction fully
// succeeds, so EnsureAssets can no-op on subsequent launches. Its contents are
// the version id, purely for eyeballing the cache.
const readyMarker = ".ready"

// prefixes are the client.jar entry prefixes we extract, mapped to where they
// land under the version dir (with the assets/minecraft/ prefix stripped so
// deepslate resolves paths like "minecraft:block/cube_all" cleanly).
var wantedPrefixes = []string{
	"assets/minecraft/blockstates/",
	"assets/minecraft/models/block/",
	"assets/minecraft/textures/block/",
	"assets/minecraft/textures/colormap/",
}

// ProgressFunc reports human-readable provisioning progress to the UI.
type ProgressFunc func(message string)

type versionManifest struct {
	Versions []struct {
		ID  string `json:"id"`
		URL string `json:"url"`
	} `json:"versions"`
}

type versionDetail struct {
	Downloads struct {
		Client struct {
			SHA1 string `json:"sha1"`
			URL  string `json:"url"`
		} `json:"client"`
	} `json:"downloads"`
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

// resolveClientJar looks up the client.jar download URL and expected sha1 for
// the given Minecraft version via Mojang's official version manifest.
func resolveClientJar(ctx context.Context, version string) (url, sha1hex string, err error) {
	var manifest versionManifest
	if err := getJSON(ctx, versionManifestURL, &manifest); err != nil {
		return "", "", fmt.Errorf("failed to fetch version manifest: %w", err)
	}
	var detailURL string
	for _, v := range manifest.Versions {
		if v.ID == version {
			detailURL = v.URL
			break
		}
	}
	if detailURL == "" {
		return "", "", fmt.Errorf("Minecraft version %q not found in Mojang manifest", version)
	}

	var detail versionDetail
	if err := getJSON(ctx, detailURL, &detail); err != nil {
		return "", "", fmt.Errorf("failed to fetch version detail for %s: %w", version, err)
	}
	if detail.Downloads.Client.URL == "" {
		return "", "", fmt.Errorf("no client download listed for Minecraft %s", version)
	}
	return detail.Downloads.Client.URL, detail.Downloads.Client.SHA1, nil
}

// VersionDir returns the cache directory for a given version's extracted
// assets: cacheRoot/<version>. Extracted files live directly under it, e.g.
// <version>/blockstates/oak_log.json, <version>/textures/block/oak_log.png.
func VersionDir(cacheRoot, version string) string {
	return filepath.Join(cacheRoot, version)
}

// IsReady reports whether the given version's assets are already fully
// extracted in the cache.
func IsReady(cacheRoot, version string) bool {
	_, err := os.Stat(filepath.Join(VersionDir(cacheRoot, version), readyMarker))
	return err == nil
}

// EnsureAssets makes the vanilla client assets for version available under
// cacheRoot, downloading and extracting the client.jar from Mojang if they are
// not already present. It is idempotent: if the ready marker exists it returns
// immediately. Extracted layout (assets/minecraft/ prefix stripped):
//
//	<cacheRoot>/<version>/blockstates/*.json
//	<cacheRoot>/<version>/models/block/*.json
//	<cacheRoot>/<version>/textures/block/*.png
//	<cacheRoot>/<version>/textures/colormap/*.png
func EnsureAssets(ctx context.Context, cacheRoot, version string, progress ProgressFunc) error {
	if progress == nil {
		progress = func(string) {}
	}
	if IsReady(cacheRoot, version) {
		return nil
	}

	destDir := VersionDir(cacheRoot, version)
	// Start from a clean slate in case a previous run was interrupted
	// mid-extraction (no marker written).
	if err := os.RemoveAll(destDir); err != nil {
		return fmt.Errorf("failed to clear stale asset dir: %w", err)
	}
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return fmt.Errorf("failed to create asset dir: %w", err)
	}

	progress(fmt.Sprintf("Resolving Minecraft %s client assets...", version))
	url, wantSHA1, err := resolveClientJar(ctx, version)
	if err != nil {
		return err
	}

	progress(fmt.Sprintf("Downloading Minecraft %s client (~25 MB)...", version))
	jarPath := filepath.Join(cacheRoot, version+".client.jar")
	if err := downloadVerified(ctx, url, wantSHA1, jarPath); err != nil {
		return err
	}
	defer os.Remove(jarPath)

	progress("Extracting block models and textures...")
	if err := extractAssets(jarPath, destDir); err != nil {
		return fmt.Errorf("failed to extract client assets: %w", err)
	}

	// Write the ready marker last so a partial extraction never looks complete.
	if err := os.WriteFile(filepath.Join(destDir, readyMarker), []byte(version), 0o644); err != nil {
		return fmt.Errorf("failed to write ready marker: %w", err)
	}
	progress(fmt.Sprintf("Minecraft %s assets ready.", version))
	return nil
}

// downloadVerified downloads fileURL to destPath, streaming through a sha1
// hash and failing if it does not match wantSHA1 (when provided by Mojang).
func downloadVerified(ctx context.Context, fileURL, wantSHA1, destPath string) error {
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
		return fmt.Errorf("unexpected status downloading client jar: %s", resp.Status)
	}

	out, err := os.Create(destPath)
	if err != nil {
		return err
	}
	hasher := sha1.New()
	if _, err := io.Copy(io.MultiWriter(out, hasher), resp.Body); err != nil {
		out.Close()
		return fmt.Errorf("failed to write client jar: %w", err)
	}
	if err := out.Close(); err != nil {
		return err
	}

	if wantSHA1 != "" {
		got := hex.EncodeToString(hasher.Sum(nil))
		if !strings.EqualFold(got, wantSHA1) {
			os.Remove(destPath)
			return fmt.Errorf("client jar sha1 mismatch: got %s, expected %s", got, wantSHA1)
		}
	}
	return nil
}

// extractAssets copies the wanted entries out of the client jar into destDir,
// stripping the assets/minecraft/ prefix. pack.mcmeta (if present) is copied to
// destDir root.
func extractAssets(jarPath, destDir string) error {
	zr, err := zip.OpenReader(jarPath)
	if err != nil {
		return err
	}
	defer zr.Close()

	for _, f := range zr.File {
		rel, ok := wantedRelPath(f.Name)
		if !ok {
			continue
		}
		if f.FileInfo().IsDir() {
			continue
		}
		if err := extractFile(f, filepath.Join(destDir, rel)); err != nil {
			return err
		}
	}
	return nil
}

// wantedRelPath decides whether a jar entry should be extracted and returns its
// destination path relative to the version dir (assets/minecraft/ stripped).
func wantedRelPath(name string) (string, bool) {
	if name == "pack.mcmeta" {
		return "pack.mcmeta", true
	}
	for _, prefix := range wantedPrefixes {
		if strings.HasPrefix(name, prefix) {
			return strings.TrimPrefix(name, "assets/minecraft/"), true
		}
	}
	return "", false
}

// extractFile writes a single zip entry to target, guarding against zip-slip.
func extractFile(f *zip.File, target string) error {
	// zip-slip guard: the cleaned target must stay within its parent.
	clean := filepath.Clean(target)
	if strings.Contains(f.Name, "..") || !strings.HasPrefix(clean, filepath.Clean(filepath.Dir(target))) {
		return fmt.Errorf("refusing to extract suspicious path: %s", f.Name)
	}
	if err := os.MkdirAll(filepath.Dir(clean), 0o755); err != nil {
		return err
	}
	rc, err := f.Open()
	if err != nil {
		return err
	}
	defer rc.Close()

	out, err := os.Create(clean)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, rc); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
