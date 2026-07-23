// Datapack import support. TreeEngineResourcePackProvider.java already scans
// DatapacksDir for ANY subfolder with a valid data/ directory and registers
// it as a top-priority server-data pack - not just the mod's own generated
// tree_engine_trees folder. This file is what lets the desktop app add a
// third-party datapack (a Modrinth zip, or an already-unzipped folder) into
// that directory from a native picker instead of requiring the user to find
// the instance folder themselves.
//
// Installing a datapack here does not take effect immediately: Minecraft
// only loads worldgen registries (configured_feature, placed_feature, etc.)
// when a world is loaded, unlike tags or loot tables which /reload can
// refresh live. The server needs a restart to pick up newly installed trees.
package instance

import (
	"archive/zip"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// DatapacksDir mirrors MainConfig.getConfigDir().resolve("datapacks") on the
// Java side.
func (l Layout) DatapacksDir() string {
	return filepath.Join(l.TreeEngineConfigDir(), "datapacks")
}

// InstallDatapackZip extracts a datapack zip into a uniquely-named folder
// under DatapacksDir and returns that folder's name.
func (l Layout) InstallDatapackZip(zipPath string) (string, error) {
	name := uniqueDatapackName(l, strings.TrimSuffix(filepath.Base(zipPath), filepath.Ext(zipPath)))
	dest := filepath.Join(l.DatapacksDir(), name)

	if err := extractDatapackZip(zipPath, dest); err != nil {
		os.RemoveAll(dest)
		return "", err
	}
	if err := flattenAndValidateDatapack(dest); err != nil {
		os.RemoveAll(dest)
		return "", err
	}
	return name, nil
}

// InstallDatapackFolder copies an existing unzipped datapack folder into a
// uniquely-named folder under DatapacksDir and returns that folder's name.
func (l Layout) InstallDatapackFolder(folderPath string) (string, error) {
	name := uniqueDatapackName(l, filepath.Base(folderPath))
	dest := filepath.Join(l.DatapacksDir(), name)

	if err := copyDir(folderPath, dest); err != nil {
		os.RemoveAll(dest)
		return "", err
	}
	if err := flattenAndValidateDatapack(dest); err != nil {
		os.RemoveAll(dest)
		return "", err
	}
	return name, nil
}

func uniqueDatapackName(l Layout, base string) string {
	base = sanitizeDatapackName(base)
	name := base
	for i := 2; ; i++ {
		if _, err := os.Stat(filepath.Join(l.DatapacksDir(), name)); os.IsNotExist(err) {
			return name
		}
		name = fmt.Sprintf("%s_%d", base, i)
	}
}

func sanitizeDatapackName(name string) string {
	name = strings.ToLower(strings.TrimSpace(name))
	var b strings.Builder
	for _, r := range name {
		if (r >= 'a' && r <= 'z') || (r >= '0' && r <= '9') || r == '_' || r == '-' {
			b.WriteRune(r)
		} else {
			b.WriteRune('_')
		}
	}
	if b.Len() == 0 {
		return "datapack"
	}
	return b.String()
}

func extractDatapackZip(zipPath, destDir string) error {
	zr, err := zip.OpenReader(zipPath)
	if err != nil {
		return fmt.Errorf("failed to open zip: %w", err)
	}
	defer zr.Close()

	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return err
	}

	for _, f := range zr.File {
		target, err := safeJoin(destDir, f.Name)
		if err != nil {
			return err
		}
		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(target, 0o755); err != nil {
				return err
			}
			continue
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		if err := extractZipEntry(f, target); err != nil {
			return err
		}
	}
	return nil
}

// safeJoin guards against zip-slip: the resulting path must stay within destDir.
func safeJoin(destDir, name string) (string, error) {
	target := filepath.Join(destDir, name)
	clean := filepath.Clean(target)
	if !strings.HasPrefix(clean, filepath.Clean(destDir)+string(os.PathSeparator)) {
		return "", fmt.Errorf("refusing to extract suspicious path: %s", name)
	}
	return clean, nil
}

func extractZipEntry(f *zip.File, target string) error {
	rc, err := f.Open()
	if err != nil {
		return err
	}
	defer rc.Close()
	out, err := os.Create(target)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, rc); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}

// flattenAndValidateDatapack handles the common case of a datapack zip/folder
// wrapping everything in one extra top-level folder, so data/ ends up
// directly under dest. Errors out if it still isn't a valid datapack -
// TreeEngineResourcePackProvider.java requires a data/ folder at the pack root.
func flattenAndValidateDatapack(dest string) error {
	if hasDataDir(dest) {
		return nil
	}
	entries, err := os.ReadDir(dest)
	if err != nil {
		return err
	}
	var dirs []os.DirEntry
	for _, e := range entries {
		if e.IsDir() {
			dirs = append(dirs, e)
		}
	}
	if len(dirs) == 1 {
		nested := filepath.Join(dest, dirs[0].Name())
		if hasDataDir(nested) {
			return flattenInto(nested, dest)
		}
	}
	return fmt.Errorf("not a valid datapack: no data/ folder found")
}

func hasDataDir(path string) bool {
	info, err := os.Stat(filepath.Join(path, "data"))
	return err == nil && info.IsDir()
}

func flattenInto(nested, dest string) error {
	entries, err := os.ReadDir(nested)
	if err != nil {
		return err
	}
	for _, e := range entries {
		if err := os.Rename(filepath.Join(nested, e.Name()), filepath.Join(dest, e.Name())); err != nil {
			return err
		}
	}
	return os.Remove(nested)
}

// copyDir recursively copies an existing datapack folder into dest.
func copyDir(src, dest string) error {
	return filepath.Walk(src, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, err := filepath.Rel(src, path)
		if err != nil {
			return err
		}
		target := filepath.Join(dest, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
			return err
		}
		in, err := os.Open(path)
		if err != nil {
			return err
		}
		defer in.Close()
		out, err := os.Create(target)
		if err != nil {
			return err
		}
		if _, err := io.Copy(out, in); err != nil {
			out.Close()
			return err
		}
		return out.Close()
	})
}
