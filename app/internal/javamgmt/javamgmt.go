// Package javamgmt finds a usable system Java installation, or downloads a
// private Temurin JRE into the app's own data directory if none is found.
package javamgmt

import (
	"archive/zip"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
)

// MinJavaMajor is the minimum Java major version the mod requires
// (mod/build.gradle pins sourceCompatibility/targetCompatibility to 21).
const MinJavaMajor = 21

const adoptiumBinaryURL = "https://api.adoptium.net/v3/binary/latest/%d/ga/windows/x64/jre/hotspot/normal/eclipse"

type Info struct {
	Path  string
	Major int
}

// FindSystemJava looks for "java" on PATH and checks it meets MinJavaMajor.
func FindSystemJava() (*Info, error) {
	path, err := exec.LookPath("java")
	if err != nil {
		return nil, errors.New("java not found on PATH")
	}
	return probe(path)
}

// ProbePath checks an explicit java(.exe) path (e.g. a previously downloaded
// private JRE) and returns its version info.
func ProbePath(path string) (*Info, error) {
	return probe(path)
}

func probe(path string) (*Info, error) {
	cmd := exec.Command(path, "-version")
	var out bytes.Buffer
	cmd.Stdout = &out
	cmd.Stderr = &out
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("failed to run %q -version: %w", path, err)
	}

	major, err := parseMajorVersion(out.String())
	if err != nil {
		return nil, err
	}
	if major < MinJavaMajor {
		return nil, fmt.Errorf("found java %d, need >= %d", major, MinJavaMajor)
	}
	return &Info{Path: path, Major: major}, nil
}

var versionRe = regexp.MustCompile(`version "(\d+)(?:\.(\d+))?`)

func parseMajorVersion(output string) (int, error) {
	m := versionRe.FindStringSubmatch(output)
	if m == nil {
		return 0, fmt.Errorf("could not parse java version from output: %s", strings.TrimSpace(output))
	}
	major, err := strconv.Atoi(m[1])
	if err != nil {
		return 0, err
	}
	// Old versioning scheme (Java 8 and earlier): "1.8.0_xxx" -> real major is the second group.
	if major == 1 && m[2] != "" {
		return strconv.Atoi(m[2])
	}
	return major, nil
}

// DownloadJRE fetches a Temurin JRE (windows-x64) matching MinJavaMajor from
// Adoptium and extracts it into destDir. Returns the path to the java.exe
// inside the extracted runtime.
func DownloadJRE(ctx context.Context, destDir string, onProgress func(downloadedBytes, totalBytes int64)) (string, error) {
	if err := os.MkdirAll(destDir, 0o755); err != nil {
		return "", fmt.Errorf("failed to create runtime dir: %w", err)
	}

	url := fmt.Sprintf(adoptiumBinaryURL, MinJavaMajor)
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}

	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		return "", fmt.Errorf("failed to download JRE: %w", err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", fmt.Errorf("unexpected status downloading JRE: %s", resp.Status)
	}

	zipPath := filepath.Join(destDir, "jre.zip")
	out, err := os.Create(zipPath)
	if err != nil {
		return "", err
	}

	var written int64
	total := resp.ContentLength
	buf := make([]byte, 256*1024)
	for {
		n, readErr := resp.Body.Read(buf)
		if n > 0 {
			if _, werr := out.Write(buf[:n]); werr != nil {
				out.Close()
				return "", werr
			}
			written += int64(n)
			if onProgress != nil {
				onProgress(written, total)
			}
		}
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			out.Close()
			return "", readErr
		}
	}
	out.Close()

	javaExe, err := extractJRE(zipPath, destDir)
	os.Remove(zipPath)
	if err != nil {
		return "", err
	}

	if _, err := probe(javaExe); err != nil {
		return "", fmt.Errorf("downloaded JRE failed verification: %w", err)
	}

	return javaExe, nil
}

// extractJRE unpacks the Adoptium zip (which contains a single top-level
// "jdk-21.x.y+z-jre" directory) into destDir and returns the path to
// bin\java.exe inside it.
func extractJRE(zipPath, destDir string) (string, error) {
	r, err := zip.OpenReader(zipPath)
	if err != nil {
		return "", fmt.Errorf("failed to open JRE archive: %w", err)
	}
	defer r.Close()

	for _, f := range r.File {
		cleanName := filepath.Clean(f.Name)
		if strings.HasPrefix(cleanName, "..") {
			return "", fmt.Errorf("unsafe path in JRE archive: %s", f.Name)
		}
		destPath := filepath.Join(destDir, cleanName)

		if f.FileInfo().IsDir() {
			if err := os.MkdirAll(destPath, 0o755); err != nil {
				return "", err
			}
			continue
		}

		if err := os.MkdirAll(filepath.Dir(destPath), 0o755); err != nil {
			return "", err
		}

		rc, err := f.Open()
		if err != nil {
			return "", err
		}
		outFile, err := os.OpenFile(destPath, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, f.Mode())
		if err != nil {
			rc.Close()
			return "", err
		}
		_, copyErr := io.Copy(outFile, rc)
		outFile.Close()
		rc.Close()
		if copyErr != nil {
			return "", copyErr
		}
	}

	// Find the extracted top-level jdk-*-jre directory.
	entries, err := os.ReadDir(destDir)
	if err != nil {
		return "", err
	}
	for _, e := range entries {
		if e.IsDir() && strings.Contains(e.Name(), "jre") {
			javaExe := filepath.Join(destDir, e.Name(), "bin", "java.exe")
			if _, err := os.Stat(javaExe); err == nil {
				return javaExe, nil
			}
		}
	}
	return "", errors.New("could not locate java.exe inside extracted JRE")
}
